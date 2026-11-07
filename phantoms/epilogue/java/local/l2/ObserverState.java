package local.l2;
import java.lang.reflect.Field;
import java.util.*;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Keeps the entry state across arena changes; never guesses GM preferences. */
public final class ObserverState {
  record Prior(boolean invisible,boolean invulnerable){}
  public static final class PriorStore<K>{
    private final Map<K,Prior> values=new WeakHashMap<>();
    synchronized void capture(K key,boolean invisible,boolean invulnerable){values.putIfAbsent(key,new Prior(invisible,invulnerable));}
    synchronized Prior remove(K key){return values.remove(key);}
  }
  static final PriorStore<Player> prior=new PriorStore<>();
  static final Field invul;
  static{try{invul=Creature.class.getDeclaredField("_isInvul");invul.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  public static void enter(Player player){
    try{prior.capture(player,player.isInvisible(),invul.getBoolean(player));}catch(IllegalAccessException e){throw new IllegalStateException(e);}
  }
  public static void leave(Player player){
    Prior value=prior.remove(player);
    if(value!=null){player.setInvisible(value.invisible());player.setInvul(value.invulnerable());}
  }
}
