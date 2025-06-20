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
package custom.CurrencyManager;

import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;

import ai.AbstractNpcAI;

/**
 * @author JeeP_
 */
public class CurrencyManager extends AbstractNpcAI
{
	private CurrencyManager()
	{
		addStartNpc(1002100);
		addFirstTalkId(1002100);
		addTalkId(1002100);
	}
	
	@Override
	public String onAdvEvent(String event, Npc npc, Player player)
	{
		switch (event)
		{
			
			case "armorshop":
				return "shoparmor.htm";
			case "weaponshop":
				return "shopweapon.htm";
			case "jewelshop":
				return "shopjewel.htm";
			default:
				return null;
		}
	}
	
	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		return "1002100.html";
	}
	
	public static void main(String[] args)
	{
		new CurrencyManager();
		
	}
}
