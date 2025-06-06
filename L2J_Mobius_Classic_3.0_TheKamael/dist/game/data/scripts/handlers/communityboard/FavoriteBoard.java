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

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * Favorite board.
 * @author Zoey76
 */
public class FavoriteBoard implements IParseBoardHandler
{
	// SQL Queries
	// private static final String SELECT_FAVORITES = "SELECT * FROM `bbs_favorites` WHERE `playerId`=? ORDER BY `favAddDate` DESC";
	
	private static final String[] COMMANDS =
	{
		"_bbsgetfav",
		"_tcservices",
		"_tcmiscellaneous"
	};
	
	@Override
	public String[] getCommunityBoardCommands()
	{
		return COMMANDS;
	}
	
	@Override
	public boolean parseCommunityBoardCommand(String command, Player player)
	{
		System.out.println("Hola desde favorito");
		System.out.println("Comando " + command);
		
		// Armar el menú de botones (merchant_nav) solo 1 vez
		StringBuilder sb = new StringBuilder();
		sb.append("<table width=180 >");
		sb.append("<tr><td height=60></td></tr>");
		
		String[][] buttons =
		{
			{
				"Home",
				"_bbsgetfav;merchant.html"
			},
			
			{
				"Services",
				"_tcservices;tcservices.html"
			},
			{
				"Miscellaneous",
				"_tcmiscellaneous;tcmiscellaneous.html"
			},
			// { "Sell", "_bbssell;merchant.html" },
			
			// otros botones si querés
		};
		
		for (String[] btn : buttons)
		{
			sb.append("<tr>");
			sb.append("<td align=\"center\">");
			sb.append("<button value=\"").append(btn[0]).append("\" action=\"bypass ").append(btn[1]).append("\" width=160 height=42 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></button>");
			sb.append("</td>");
			sb.append("</tr>");
		}
		sb.append("</table>");
		
		if (command.startsWith("_bbsgetfav"))
		{
			String htmlTemplate = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/merchant.html");
			htmlTemplate = htmlTemplate.replace("%merchant_nav%", sb.toString());
			CommunityBoardHandler.separateAndSend(htmlTemplate, player);
			
		}
		else if (command.startsWith("_tcservices"))
		{
			String htmlTemplate = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/tcservices.html");
			htmlTemplate = htmlTemplate.replace("%merchant_nav%", sb.toString());
			CommunityBoardHandler.separateAndSend(htmlTemplate, player);
		}
		else if (command.startsWith("_tcmiscellaneous"))
		{
			String htmlTemplate = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/tcmiscellaneous.html");
			htmlTemplate = htmlTemplate.replace("%merchant_nav%", sb.toString());
			CommunityBoardHandler.separateAndSend(htmlTemplate, player);
		}
		
		else if (command.startsWith("_bbsmultisell") || command.startsWith("_bbsexcmultisell") || command.startsWith("_bbssell"))
		{
			return false; // Dejalo que lo maneje otro handler como HomeBoard
		}
		
		return true;
	}
	
}
