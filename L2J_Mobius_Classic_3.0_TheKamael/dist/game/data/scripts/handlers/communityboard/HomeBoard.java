/*
 * This file is part of the L2J Mobius project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package handlers.communityboard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.l2jmobius.Config;
import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.data.SchemeBufferTable;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.BuyListData;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.instancemanager.PremiumManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.instance.Pet;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.BuyList;
import org.l2jmobius.gameserver.network.serverpackets.ExBuySellList;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.ShowBoard;
import org.l2jmobius.gameserver.util.Util;

/**
 * Home board.
 * @author Zoey76, Mobius
 */
public class HomeBoard implements IParseBoardHandler
{
	// SQL Queries
	private static final String COUNT_FAVORITES = "SELECT COUNT(*) AS favorites FROM `bbs_favorites` WHERE `playerId`=?";
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final int PAGE_LIMIT = 6;
	private static final String[] COMMANDS =
	{
		"_bbshome",
		"_bbstop",
	};
	
	private static final String[] CUSTOM_COMMANDS =
	{
		Config.PREMIUM_SYSTEM_ENABLED && Config.COMMUNITY_PREMIUM_SYSTEM_ENABLED ? "_bbspremium" : null,
		Config.COMMUNITYBOARD_ENABLE_MULTISELLS ? "_bbsexcmultisell" : null,
		Config.COMMUNITYBOARD_ENABLE_MULTISELLS ? "_bbsmultisell" : null,
		Config.COMMUNITYBOARD_ENABLE_MULTISELLS ? "_bbssell" : null,
		Config.COMMUNITYBOARD_ENABLE_TELEPORTS ? "_bbsteleport" : null,
		Config.COMMUNITYBOARD_ENABLE_BUFFS ? "_bbsbuff" : null,
		Config.COMMUNITYBOARD_ENABLE_BUFFS ? "_bbscreatescheme" : null,
		Config.COMMUNITYBOARD_ENABLE_BUFFS ? "_bbseditscheme" : null,
		Config.COMMUNITYBOARD_ENABLE_BUFFS ? "_bbsdeletescheme" : null,
		Config.COMMUNITYBOARD_ENABLE_BUFFS ? "_bbsskillselect" : null,
		Config.COMMUNITYBOARD_ENABLE_BUFFS ? "_bbsskillunselect" : null,
		Config.COMMUNITYBOARD_ENABLE_BUFFS ? "_bbsgivebuffs" : null,
		Config.COMMUNITYBOARD_ENABLE_HEAL ? "_bbsheal" : null,
		Config.COMMUNITYBOARD_ENABLE_DELEVEL ? "_bbsdelevel" : null
	};
	private static final BiPredicate<String, Player> COMBAT_CHECK = (command, player) ->
	{
		boolean commandCheck = false;
		for (String c : CUSTOM_COMMANDS)
		{
			if ((c != null) && command.startsWith(c))
			{
				commandCheck = true;
				break;
			}
		}
		return commandCheck && (player.isCastingNow() || player.isInCombat() || player.isInDuel() || player.isInOlympiadMode() || player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP));
	};
	
	private static final Predicate<Player> KARMA_CHECK = player -> Config.COMMUNITYBOARD_KARMA_DISABLED && (player.getReputation() < 0);
	
	@Override
	public String[] getCommunityBoardCommands()
	{
		final List<String> commands = new ArrayList<>();
		commands.addAll(Arrays.asList(COMMANDS));
		commands.addAll(Arrays.asList(CUSTOM_COMMANDS));
		return commands.stream().filter(Objects::nonNull).toArray(String[]::new);
	}
	
	@Override
	public boolean parseCommunityBoardCommand(String command, Player player)
	{
		// Old custom conditions check move to here
		if (COMBAT_CHECK.test(command, player))
		{
			player.sendMessage("You can't use the Community Board right now.");
			return false;
		}
		
		if (KARMA_CHECK.test(player))
		{
			player.sendMessage("Players with Karma cannot use the Community Board.");
			return false;
		}
		
		String returnHtml = null;
		final String navigation = HtmCache.getInstance().getHtm(player, NAVIGATION_PATH);
		
		String clanName = (player.getClan() != null) ? player.getClan().getName() : "Sin clan";
		String premiumStatus = player.hasPremiumStatus() ? "Sí" : "No";
		String pvpKills = Integer.toString(player.getPvpKills());
		String pkKills = Integer.toString(player.getPkKills());
		String vipExpirationFormatted = "No VIP activo";
		final long endDate = PremiumManager.getInstance().getPremiumExpiration(player.getAccountName());
		if (endDate > System.currentTimeMillis())
		{
			vipExpirationFormatted = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(endDate));
		}
		
		String navigationWithVars = navigation.replace("%player_name%", player.getName()).replace("%account_name%", player.getAccountName()).replace("%clan_name%", clanName).replace("%premium_status%", premiumStatus).replace("%pvp_kills%", pvpKills).replace("%pk_kills%", pkKills).replace("%vip_expiration%", vipExpirationFormatted);
		if (command.equals("_bbshome") || command.equals("_bbstop"))
		{
			final String customPath = Config.CUSTOM_CB_ENABLED ? "Custom/" : "";
			CommunityBoardHandler.getInstance().addBypass(player, "Home", command);
			
			// Cargamos el HTML principal
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/" + customPath + "home.html");
			
			// Insertamos el navigation modificado
			returnHtml = returnHtml.replace("%navigation%", navigationWithVars);
			
			if (!Config.CUSTOM_CB_ENABLED)
			{
				returnHtml = returnHtml.replace("%fav_count%", Integer.toString(getFavoriteCount(player)));
				returnHtml = returnHtml.replace("%region_count%", Integer.toString(getRegionCount(player)));
				returnHtml = returnHtml.replace("%clan_count%", Integer.toString(ClanTable.getInstance().getClanCount()));
			}
		}
		else if (command.startsWith("_bbstop;"))
		{
			final String customPath = Config.CUSTOM_CB_ENABLED ? "Custom/" : "";
			final String path = command.replace("_bbstop;", "");
			if ((path.length() > 0) && path.endsWith(".html"))
			{
				returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/" + customPath + path);
				// Agregado reemplazo para que no pierda la navegación y los datos dinámicos
				if ((returnHtml != null) && returnHtml.contains("%navigation%"))
				{
					returnHtml = returnHtml.replace("%navigation%", navigationWithVars);
				}
			}
		}
		else if (command.startsWith("_bbsmultisell"))
		{
			final String fullBypass = command.replace("_bbsmultisell;", "");
			final String[] buypassOptions = fullBypass.split(",");
			final int multisellId = Integer.parseInt(buypassOptions[0]);
			final String page = buypassOptions[1];
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
			MultisellData.getInstance().separateAndSend(multisellId, player, null, false);
		}
		else if (command.startsWith("_bbsexcmultisell"))
		{
			final String fullBypass = command.replace("_bbsexcmultisell;", "");
			final String[] buypassOptions = fullBypass.split(",");
			final int multisellId = Integer.parseInt(buypassOptions[0]);
			final String page = buypassOptions[1];
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
			MultisellData.getInstance().separateAndSend(multisellId, player, null, true);
		}
		else if (command.startsWith("_bbssell"))
		{
			final String page = command.replace("_bbssell;", "");
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
			player.sendPacket(new BuyList(BuyListData.getInstance().getBuyList(423), player, 0));
			player.sendPacket(new ExBuySellList(player, false));
		}
		else if (command.startsWith("_bbsteleport"))
		{
			final String teleBuypass = command.replace("_bbsteleport;", "");
			if (player.getInventory().getInventoryItemCount(Config.COMMUNITYBOARD_CURRENCY, -1) < Config.COMMUNITYBOARD_TELEPORT_PRICE)
			{
				player.sendMessage("Not enough currency!");
			}
			else if (Config.COMMUNITY_AVAILABLE_TELEPORTS.get(teleBuypass) != null)
			{
				player.disableAllSkills();
				player.sendPacket(new ShowBoard());
				player.destroyItemByItemId("CB_Teleport", Config.COMMUNITYBOARD_CURRENCY, Config.COMMUNITYBOARD_TELEPORT_PRICE, player, true);
				player.setInstanceById(0);
				player.teleToLocation(Config.COMMUNITY_AVAILABLE_TELEPORTS.get(teleBuypass), 0);
				ThreadPool.schedule(player::enableAllSkills, 3000);
			}
		}
		else if (command.startsWith("_bbsbuff"))
		{
			final String fullBypass = command.replace("_bbsbuff;", "");
			final String[] buypassOptions = fullBypass.split(";");
			final int buffCount = buypassOptions.length - 1;
			final String page = buypassOptions[buffCount];
			if (player.getInventory().getInventoryItemCount(Config.COMMUNITYBOARD_CURRENCY, -1) < (Config.COMMUNITYBOARD_BUFF_PRICE * buffCount))
			{
				player.sendMessage("Not enough currency!");
			}
			else
			{
				player.destroyItemByItemId("CB_Buff", Config.COMMUNITYBOARD_CURRENCY, Config.COMMUNITYBOARD_BUFF_PRICE * buffCount, player, true);
				final Pet pet = player.getPet();
				final List<Creature> targets = new ArrayList<>(4);
				targets.add(player);
				if (pet != null)
				{
					targets.add(pet);
				}
				
				player.getServitors().values().stream().forEach(targets::add);
				
				for (int i = 0; i < buffCount; i++)
				{
					final Skill skill = SkillData.getInstance().getSkill(Integer.parseInt(buypassOptions[i].split(",")[0]), Integer.parseInt(buypassOptions[i].split(",")[1]));
					if (!Config.COMMUNITY_AVAILABLE_BUFFS.contains(skill.getId()))
					{
						continue;
					}
					targets.stream().filter(target -> !target.isSummon() || !skill.isSharedWithSummon()).forEach(target ->
					{
						skill.applyEffects(player, target);
						if (Config.COMMUNITYBOARD_CAST_ANIMATIONS)
						{
							player.sendPacket(new MagicSkillUse(player, target, skill.getId(), skill.getLevel(), skill.getHitTime(), skill.getReuseDelay()));
							// not recommend broadcast
							// player.broadcastPacket(new MagicSkillUse(player, target, skill.getId(),
							// skill.getLevel(), skill.getHitTime(), skill.getReuseDelay()));
						}
					});
				}
			}
			
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
		}
		else if (command.startsWith("_bbscreatescheme"))
		{
			command = command.replace("_bbscreatescheme ", "");
			
			boolean canCreateScheme = true;
			
			try
			{
				// Check if more then 14 chars
				final String schemeName = command.trim();
				if (schemeName.length() > 14)
				{
					player.sendMessage("Scheme's name must contain up to 14 chars.");
					canCreateScheme = false;
				}
				
				// Simple hack to use spaces, dots, commas, minus, plus, exclamations or
				// question marks.
				if (!Util.isAlphaNumeric(schemeName.replace(" ", "").replace(".", "").replace(",", "").replace("-", "").replace("+", "").replace("!", "").replace("?", "")))
				{
					player.sendMessage("Please use plain alphanumeric characters.");
					canCreateScheme = false;
				}
				
				final Map<String, List<Integer>> schemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
				if (schemes != null)
				{
					if (schemes.size() == Config.BUFFER_MAX_SCHEMES)
					{
						player.sendMessage("Maximum schemes amount is already reached.");
						canCreateScheme = false;
					}
					if (schemes.containsKey(schemeName))
					{
						player.sendMessage("The scheme name already exists.");
						canCreateScheme = false;
					}
				}
				
				if (canCreateScheme)
				{
					SchemeBufferTable.getInstance().setScheme(player.getObjectId(), schemeName.trim(), new ArrayList<>());
				}
			}
			catch (Exception e)
			{
				player.sendMessage(e.getMessage());
			}
			
			// Cargamos el HTML principal
			String mainHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/buffer/main.html");
			
			// Navigation custom
			String nav = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/buffer/navigation.html");
			
			String navParsed = nav.replace("%player_name%", player.getName()).replace("%account_name%", player.getAccountName()).replace("%clan_name%", player.getClan() != null ? player.getClan().getName() : "Sin clan").replace("%premium_status%", player.hasPremiumStatus() ? "Sí" : "No").replace("%pvp_kills%", String.valueOf(player.getPvpKills())).replace("%pk_kills%", String.valueOf(player.getPkKills()));
			
			// Insertamos el navigation con datos del player
			returnHtml = mainHtml.replace("%navigation%", navParsed);
			
		}
		else if (command.startsWith("_bbseditscheme"))
		{
			// Simple hack to use createscheme bypass with a space.
			command = command.replace("_bbseditscheme ", "_bbseditscheme;");
			
			final StringTokenizer st = new StringTokenizer(command, ";");
			final String currentCommand = st.nextToken();
			
			final String groupType = st.nextToken();
			final String schemeName = st.nextToken();
			final int page = Integer.parseInt(st.nextToken());
			
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/buffer/scheme.html");
			
			final List<Integer> schemeSkills = SchemeBufferTable.getInstance().getScheme(player.getObjectId(), schemeName);
			returnHtml = returnHtml.replace("%schemename%", schemeName);
			returnHtml = returnHtml.replace("%count%", getCountOf(schemeSkills, false) + " / " + player.getStat().getMaxBuffCount() + " buffs, " + getCountOf(schemeSkills, true) + " / " + Config.DANCES_MAX_AMOUNT + " dances/songs");
			returnHtml = returnHtml.replace("%typesframe%", getTypesFrame(groupType, schemeName));
			returnHtml = returnHtml.replace("%skilllistframe%", getGroupSkillList(player, groupType, schemeName, page));
		}
		else if (command.startsWith("_bbsskill"))
		{
			final StringTokenizer st = new StringTokenizer(command, ";");
			final String currentCommand = st.nextToken();
			
			final String groupType = st.nextToken();
			final String schemeName = st.nextToken();
			final int skillId = Integer.parseInt(st.nextToken());
			final int page = Integer.parseInt(st.nextToken());
			final List<Integer> skills = SchemeBufferTable.getInstance().getScheme(player.getObjectId(), schemeName);
			
			if (currentCommand.startsWith("_bbsskillselect") && !schemeName.equalsIgnoreCase("none"))
			{
				final Skill skill = SkillData.getInstance().getSkill(skillId, SkillData.getInstance().getMaxLevel(skillId));
				if (skill.isDance())
				{
					if (getCountOf(skills, true) < Config.DANCES_MAX_AMOUNT)
					{
						skills.add(skillId);
					}
					else
					{
						player.sendMessage("This scheme has reached the maximum amount of dances/songs.");
					}
				}
				else
				{
					if (getCountOf(skills, false) < player.getStat().getMaxBuffCount())
					{
						skills.add(skillId);
					}
					else
					{
						player.sendMessage("This scheme has reached the maximum amount of buffs.");
					}
				}
			}
			else if (currentCommand.startsWith("_bbsskillunselect"))
			{
				skills.remove(Integer.valueOf(skillId));
			}
			
			returnHtml = showEditSchemeWindow(player, groupType, schemeName, page, returnHtml);
		}
		else if (command.startsWith("_bbsgivebuffs"))
		{
			final StringTokenizer st = new StringTokenizer(command, ";");
			final String currentCommand = st.nextToken();
			
			final String schemeName = st.nextToken();
			final long cost = Integer.parseInt(st.nextToken());
			Creature target = null;
			if (st.hasMoreTokens())
			{
				final String targetType = st.nextToken();
				if ((targetType != null) && targetType.equalsIgnoreCase("pet"))
				{
					target = player.getPet();
				}
				else if ((targetType != null) && targetType.equalsIgnoreCase("summon"))
				{
					for (Summon summon : player.getServitorsAndPets())
					{
						if (summon.isServitor())
						{
							target = summon;
						}
					}
				}
			}
			else
			{
				target = player;
			}
			
			if (target == null)
			{
				player.sendMessage("You don't have a pet.");
			}
			else if ((cost == 0) || player.reduceAdena("Community Board Buffer", cost, target, true))
			{
				for (int skillId : SchemeBufferTable.getInstance().getScheme(player.getObjectId(), schemeName))
				{
					SkillData.getInstance().getSkill(skillId, SkillData.getInstance().getMaxLevel(skillId)).applyEffects(target, target);
				}
			}
			
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/buffer/main.html");
		}
		else if (command.startsWith("_bbsdeletescheme"))
		{
			
			final StringTokenizer st = new StringTokenizer(command, ";");
			final String currentCommand = st.nextToken();
			
			final String schemeName = st.nextToken();
			final Map<String, List<Integer>> schemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
			if ((schemes != null) && schemes.containsKey(schemeName))
			{
				schemes.remove(schemeName);
			}
			
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/buffer/main.html");
		}
		else if (command.startsWith("_bbsheal"))
		{
			final String page = command.replace("_bbsheal;", "");
			if (player.getInventory().getInventoryItemCount(Config.COMMUNITYBOARD_CURRENCY, -1) < (Config.COMMUNITYBOARD_HEAL_PRICE))
			{
				player.sendMessage("Not enough currency!");
			}
			else
			{
				player.destroyItemByItemId("CB_Heal", Config.COMMUNITYBOARD_CURRENCY, Config.COMMUNITYBOARD_HEAL_PRICE, player, true);
				player.setCurrentHp(player.getMaxHp());
				player.setCurrentMp(player.getMaxMp());
				player.setCurrentCp(player.getMaxCp());
				if (player.hasPet())
				{
					player.getPet().setCurrentHp(player.getPet().getMaxHp());
					player.getPet().setCurrentMp(player.getPet().getMaxMp());
					player.getPet().setCurrentCp(player.getPet().getMaxCp());
				}
				for (Summon summon : player.getServitors().values())
				{
					summon.setCurrentHp(summon.getMaxHp());
					summon.setCurrentMp(summon.getMaxMp());
					summon.setCurrentCp(summon.getMaxCp());
				}
				player.sendMessage("You used heal!");
			}
			
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
		}
		else if (command.equals("_bbsdelevel"))
		{
			if (player.getInventory().getInventoryItemCount(Config.COMMUNITYBOARD_CURRENCY, -1) < Config.COMMUNITYBOARD_DELEVEL_PRICE)
			{
				player.sendMessage("Not enough currency!");
			}
			else if (player.getLevel() == 1)
			{
				player.sendMessage("You are at minimum level!");
			}
			else
			{
				player.destroyItemByItemId("CB_Delevel", Config.COMMUNITYBOARD_CURRENCY, Config.COMMUNITYBOARD_DELEVEL_PRICE, player, true);
				final int newLevel = player.getLevel() - 1;
				player.setExp(ExperienceData.getInstance().getExpForLevel(newLevel));
				player.getStat().setLevel((byte) newLevel);
				player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
				player.setCurrentCp(player.getMaxCp());
				player.broadcastUserInfo();
				player.checkPlayerSkills(); // Adjust skills according to new level.
				returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/delevel/complete.html");
			}
		}
		else if (command.startsWith("_bbspremium"))
		{
			final String fullBypass = command.replace("_bbspremium;", "");
			final String[] buypassOptions = fullBypass.split(",");
			final int premiumDays = Integer.parseInt(buypassOptions[0]);
			if (player.getInventory().getInventoryItemCount(Config.COMMUNITY_PREMIUM_COIN_ID, -1) < (Config.COMMUNITY_PREMIUM_PRICE_PER_DAY * premiumDays))
			{
				player.sendMessage("Not enough currency!");
			}
			else
			{
				player.destroyItemByItemId("CB_Premium", Config.COMMUNITY_PREMIUM_COIN_ID, Config.COMMUNITY_PREMIUM_PRICE_PER_DAY * premiumDays, player, true);
				PremiumManager.getInstance().addPremiumTime(player.getAccountName(), premiumDays, TimeUnit.DAYS);
				player.sendMessage("Your account will now have premium status until " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(PremiumManager.getInstance().getPremiumExpiration(player.getAccountName())) + ".");
				returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/premium/thankyou.html");
				returnHtml = returnHtml.replace("%playername%", player.getName());
				
			}
		}
		if (returnHtml != null)
		{
			if (Config.CUSTOM_CB_ENABLED)
			{
				final StringBuilder sb = new StringBuilder(200);
				
				final Map<String, List<Integer>> schemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
				if ((schemes == null) || schemes.isEmpty())
				{
					// sb.append("<tr><td align=center><font color=\"LEVEL\">0/4 schemes
					// defined.</font></td></tr>");
				}
				else
				{
					for (Map.Entry<String, List<Integer>> scheme : schemes.entrySet())
					{
						final int cost = getFee(scheme.getValue());
						sb.append("<tr><td align=center><button value=\"" + scheme.getKey() + "\" action=\"bypass _bbsgivebuffs;" + scheme.getKey() + ";" + cost + "\" width=128 height=28 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
						sb.append("<td align=center><button value=\"Pet\" action=\"bypass _bbsgivebuffs;" + scheme.getKey() + ";" + cost + ";pet\" width=92 height=28 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
						sb.append("<td align=center><button value=\"Summon\" action=\"bypass _bbsgivebuffs;" + scheme.getKey() + ";" + cost + ";summon\" width=92 height=28 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
						sb.append("<td align=center><button value=\"Edit\" action=\"bypass _bbseditscheme;Buffs;" + scheme.getKey() + ";1\" width=64 height=28 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
						sb.append("<td align=center><button value=\"X\" action=\"bypass _bbsdeletescheme;" + scheme.getKey() + ";1\" width=32 height=28 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr>");
					}
				}
				returnHtml = returnHtml.replace("%schemes%", sb.toString());
				returnHtml = returnHtml.replace("%max_schemes%", Integer.toString(Config.BUFFER_MAX_SCHEMES));
				
				returnHtml = returnHtml.replace("%navigation%", navigation);
			}
			CommunityBoardHandler.separateAndSend(returnHtml, player);
		}
		return false;
	}
	
	/**
	 * Gets the Favorite links for the given player.
	 * @param player the player
	 * @return the favorite links count
	 */
	private static int getFavoriteCount(Player player)
	{
		int count = 0;
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(COUNT_FAVORITES))
		{
			ps.setInt(1, player.getObjectId());
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					count = rs.getInt("favorites");
				}
			}
		}
		catch (Exception e)
		{
			LOG.warning(FavoriteBoard.class.getSimpleName() + ": Coudn't load favorites count for player " + player.getName());
		}
		return count;
	}
	
	/**
	 * Gets the registered regions count for the given player.
	 * @param player the player
	 * @return the registered regions count
	 */
	private static int getRegionCount(Player player)
	{
		return 0; // TODO: Implement.
	}
	
	/**
	 * This sends an html packet to player with Edit Scheme Menu info. This allows player to edit each created scheme (add/delete skills)
	 * @param player : The player to make checks on.
	 * @param groupType : The group of skills to select.
	 * @param schemeName : The scheme to make check.
	 * @param page The page.
	 * @param returnHtml to return.
	 */
	private String showEditSchemeWindow(Player player, String groupType, String schemeName, int page, String returnHtml)
	{
		returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/buffer/scheme.html");
		
		final List<Integer> schemeSkills = SchemeBufferTable.getInstance().getScheme(player.getObjectId(), schemeName);
		returnHtml = returnHtml.replace("%schemename%", schemeName);
		returnHtml = returnHtml.replace("%count%", getCountOf(schemeSkills, false) + " / " + player.getStat().getMaxBuffCount() + " buffs, " + getCountOf(schemeSkills, true) + " / " + Config.DANCES_MAX_AMOUNT + " dances/songs");
		returnHtml = returnHtml.replace("%typesframe%", getTypesFrame(groupType, schemeName));
		returnHtml = returnHtml.replace("%skilllistframe%", getGroupSkillList(player, groupType, schemeName, page));
		
		return returnHtml;
	}
	
	/**
	 * @param player : The player to make checks on.
	 * @param groupType : The group of skills to select.
	 * @param schemeName : The scheme to make check.
	 * @param page The page.
	 * @return a String representing skills available to selection for a given groupType.
	 */
	private String getGroupSkillList(Player player, String groupType, String schemeName, int page)
	{
		// Retrieve the entire skills list based on group type.
		List<Integer> skills = SchemeBufferTable.getInstance().getSkillsIdsByType(groupType);
		if (skills.isEmpty())
		{
			return "That group doesn't contain any skills.";
		}
		
		// Calculate page number.
		final int max = countPagesNumber(skills.size(), PAGE_LIMIT);
		if (page > max)
		{
			page = max;
		}
		
		// Cut skills list up to page number.
		// skills = skills.subList((page - 1) * PAGE_LIMIT, Math.min(page * PAGE_LIMIT,
		// skills.size()));
		
		final List<Integer> schemeSkills = SchemeBufferTable.getInstance().getScheme(player.getObjectId(), schemeName);
		final StringBuilder sb = new StringBuilder(skills.size() * 200);
		int column = 0;
		int maxColumn = 3; // para que no se desborde
		
		sb.append("<table  cellpadding='4' cellspacing=5 width=90%>");
		
		for (int skillId : skills)
		{
			if (column == 0)
			{
				sb.append("<tr>");
			}
			
			final Skill skill = SkillData.getInstance().getSkill(skillId, 1);
			String desc = SchemeBufferTable.getInstance().getAvailableBuff(skillId).getDescription();
			
			sb.append("<td height=40 width=40 valign=top><img src=\"").append(skill.getIcon()).append("\" width=32 height=32></td>");
			sb.append("<td width=190 valign=top>").append(skill.getName()).append("<br1><font color=\"B09878\">").append(desc).append("</font></td>");
			
			if (schemeSkills.contains(skillId))
			{
				sb.append("<td valign=top><button value=\" \" action=\"bypass _bbsskillunselect;").append(groupType).append(";").append(schemeName).append(";").append(skillId).append(";").append(page).append("\" width=32 height=32 back=\"L2UI_CH3.mapbutton_zoomout2\" fore=\"L2UI_CH3.mapbutton_zoomout1\"></td>");
			}
			else
			{
				sb.append("<td valign=top><button value=\" \" action=\"bypass _bbsskillselect;").append(groupType).append(";").append(schemeName).append(";").append(skillId).append(";").append(page).append("\" width=32 height=32 back=\"L2UI_CH3.mapbutton_zoomin2\" fore=\"L2UI_CH3.mapbutton_zoomin1\"></td>");
			}
			
			column++;
			
			if (column == maxColumn)
			{
				sb.append("</tr>");
				column = 0;
			}
		}
		
		if (column != 0)
		{
			sb.append("</tr>");
		}
		
		sb.append("</table>");
		
		// Build page footer.
		/*
		 * sb.append("<br><table><tr>"); if (page > 1) { sb.append("<td align=left width=70><a action=\"bypass _bbseditschemes;" + groupType + ";" + schemeName + ";" + (page - 1) + "\">Previous</a></td>"); } else { sb.append("<td align=left width=70>Previous</td>"); }
		 * sb.append("<td align=center width=100>Page " + page + "</td>"); if (page < max) { sb.append("<td align=right width=70><a action=\"bypass _bbseditschemes;" + groupType + ";" + schemeName + ";" + (page + 1) + "\">Next</a></td>"); } else { sb.append("<td align=right width=70>Next</td>"); }
		 * sb.append("</tr></table>");
		 */
		
		return sb.toString();
	}
	
	/**
	 * @param groupType : The group of skills to select.
	 * @param schemeName : The scheme to make check.
	 * @return a string representing all groupTypes available. The group currently on selection isn't linkable.
	 */
	private static String getTypesFrame(String groupType, String schemeName)
	{
		final StringBuilder sb = new StringBuilder(500);
		sb.append("<table>");
		
		int count = 0;
		for (String type : SchemeBufferTable.getInstance().getSkillTypes())
		{
			if (count == 0)
			{
				sb.append("<tr>");
			}
			
			if (groupType.equalsIgnoreCase(type))
			{
				sb.append("<td width=65>" + type + "</td>");
			}
			else
			{
				sb.append("<td width=65><a action=\"bypass _bbseditschemes;" + type + ";" + schemeName + ";1\">" + type + "</a></td>");
			}
			
			count++;
			if (count == 4)
			{
				sb.append("</tr>");
				count = 0;
			}
		}
		
		if (!sb.toString().endsWith("</tr>"))
		{
			sb.append("</tr>");
		}
		
		sb.append("</table>");
		
		return sb.toString();
	}
	
	/**
	 * @param list : A list of skill ids.
	 * @return a global fee for all skills contained in list.
	 */
	private static int getFee(List<Integer> list)
	{
		if (Config.BUFFER_STATIC_BUFF_COST > 0)
		{
			return list.size() * Config.BUFFER_STATIC_BUFF_COST;
		}
		
		int fee = 0;
		for (int sk : list)
		{
			fee += SchemeBufferTable.getInstance().getAvailableBuff(sk).getPrice();
		}
		
		return fee;
	}
	
	private static int countPagesNumber(int objectsSize, int pageSize)
	{
		return (objectsSize / pageSize) + ((objectsSize % pageSize) == 0 ? 0 : 1);
	}
	
	private static long getCountOf(List<Integer> skills, boolean dances)
	{
		return skills.stream().filter(sId -> SkillData.getInstance().getSkill(sId, 1).isDance() == dances).count();
	}
}
