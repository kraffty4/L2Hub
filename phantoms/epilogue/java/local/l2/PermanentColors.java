package local.l2;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;

/** Explicit admin and paid choices persist. Temporary event colors keep native behavior. */
public final class PermanentColors {
  public static final String NAME="L2Local.AdminNameColor",TITLE="L2Local.AdminTitleColor";
  public static boolean valid(int value){return value>=0&&value<=0xffffff;}
  public static void name(PlayerAppearance appearance,int value){
    if(!valid(value))throw new IllegalArgumentException("Color must have six hexadecimal digits");
    appearance.setNameColor(value);save(appearance.getOwner(),NAME,value);
  }
  public static void title(PlayerAppearance appearance,int value){
    if(!valid(value))throw new IllegalArgumentException("Color must have six hexadecimal digits");
    appearance.setTitleColor(value);save(appearance.getOwner(),TITLE,value);
  }
  static void save(Player player,String key,int value){
    var variables=player.getVariables();variables.set(key,value);
    if(variables.hasChanges()&&!variables.saveNow())player.sendMessage("Color changed, but could not be saved. Please contact the administrator.");
  }
  public static void restoreIfLoaded(Player player){if(player.hasVariables())restore(player);}
  public static void restore(Player player){
    var variables=player.getVariables();
    int name=variables.getInt(NAME,-1),title=variables.getInt(TITLE,-1);
    if(valid(name))player.getAppearance().setNameColor(name);
    if(valid(title))player.getAppearance().setTitleColor(title);
  }
}
