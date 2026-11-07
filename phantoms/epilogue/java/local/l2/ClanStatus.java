package local.l2;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListUpdate;

public final class ClanStatus {
  public static void entered(Player player){
    PermanentColors.restore(player);
    var clan=player.getClan();
    if(clan==null)return;
    var member=clan.getClanMember(player.getObjectId());
    if(member!=null)member.setPlayer(player);
    clan.broadcastToOtherOnlineMembers(new PledgeShowMemberListUpdate(player),player);
  }
}
