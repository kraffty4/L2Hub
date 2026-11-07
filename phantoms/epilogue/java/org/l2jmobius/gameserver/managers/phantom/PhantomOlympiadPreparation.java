package org.l2jmobius.gameserver.managers.phantom;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.*;
import org.l2jmobius.gameserver.entity.actor.instance.OlympiadManager;
import org.l2jmobius.gameserver.mechanics.skill.*;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;
import static org.l2jmobius.gameserver.managers.phantom.OlympiadPreparationEngine.*;

/** Arena lifecycle adapter. Player/NPC actions keep native costs, permissions and effect handling. */
public final class PhantomOlympiadPreparation {
  private static final Logger LOG=Logger.getLogger(PhantomOlympiadPreparation.class.getName());
  private static final Map<Integer,Arena> ACTIVE=new ConcurrentHashMap<>();
  private PhantomOlympiadPreparation(){}
  private static boolean hunter(Player p){
    if(p==null||!PhantomManager.getInstance().isPhantom(p))return false;
    PhantomTemplate t=PhantomData.getInstance().getTemplate(p.getName());
    return t!=null&&t.isHunter()&&!t.isReaper();
  }
  public static void afterRemovals(Player p,Npc npc,int game){
    if(!PhantomHuntersConfig.HUNTER_OLYMPIAD_PREPARATION)return;
    if(!hunter(p)||!p.isInOlympiadMode()||p.isOlympiadStart()||p.isDead()||p.getOlympiadGameId()!=game)return;
    Arena next=new Arena(p,npc,game,System.currentTimeMillis());
    ACTIVE.put(p.getObjectId(),next);
    AutoPlayTaskManager.getInstance().stopAutoPlay(p);
    AutoUseTaskManager.getInstance().stopAutoUseTask(p);
    LOG.info("Olympiad prep started: "+p.getName()+"; game="+game+"; profile="+next.profile+"; NPC quota="+p.getOlympiadBuffCount());
  }
  public static void tick(Player p,long now){
    Arena arena=ACTIVE.get(p.getObjectId());if(arena==null)return;
    synchronized(arena){
      if(!PhantomHuntersConfig.HUNTER_OLYMPIAD_PREPARATION||!arena.beforeBattle()||p.isDead()){clear(p);return;}
      try{arena.engine.tick(arena,now);if(arena.engine.done()){arena.restoreShots();arena.report("ready");}}
      catch(RuntimeException error){arena.engine.seal();arena.report("failed: "+error.getClass().getSimpleName());}
    }
  }
  public static void atOpenGates(Player p){seal(p,"gates open");}
  public static void beforeCompetition(Player one,Player two){seal(one,"battle start");seal(two,"battle start");}
  public static void clearGame(Player one,Player two){seal(one,"match cleanup");seal(two,"match cleanup");}
  public static void clear(Player p){if(p!=null)seal(p,"left arena");}
  private static void seal(Player p,String why){
    if(p==null)return;Arena arena=ACTIVE.remove(p.getObjectId());if(arena==null)return;
    synchronized(arena){arena.engine.seal();if((why.equals("battle start")||why.equals("gates open"))&&PhantomHuntersConfig.HUNTER_OLYMPIAD_PREPARATION)arena.restoreShots();arena.report(why);}
  }
  private static final class Arena implements Context {
    final Player player;final Npc buffer;final int game,side;
    final OlympiadPreparationPlan.Profile profile;
    final OlympiadPreparationEngine engine;
    boolean reported,shots;
    Arena(Player p,Npc buffer,int game,long now){
      player=p;this.buffer=buffer;this.game=game;side=p.getOlympiadSide();
      profile=OlympiadPreparationPlan.profile(p.getPlayerClass().getId(),p.isMageClass());
      engine=new OlympiadPreparationEngine(now,OlympiadPreparationPlan.npcOrder(profile),55000);
    }
    public boolean beforeBattle(){return player.isInOlympiadMode()&&!player.isOlympiadStart()&&player.getOlympiadGameId()==game&&player.getOlympiadSide()==side;}
    public boolean available(){return !player.isDead()&&!player.isTeleporting();}
    public boolean casting(){return player.isCastingNow();}
    public int npcRemaining(){return player.getOlympiadBuffCount();}
    public boolean npcReady(){return buffer instanceof OlympiadManager&&buffer.getId()==36402&&buffer.isSpawned()&&!buffer.isDecayed()&&buffer.getInstanceId()==player.getInstanceId();}
    public boolean npcCovered(int index){Skill s=OlympiadPreparationPlan.npcSkill(index);return s==null||buffCovered(player,s);}
    public Request npc(int index){
      if(!beforeBattle()||!npcReady()||npcRemaining()<=0)return Request.FAILED;
      if(!buffer.isInsideRadius3D(player,Npc.INTERACTION_DISTANCE)){
        if(player.calculateDistance2D(buffer)>800)return Request.FAILED;
        if(!player.isMoving()){player.setRunning();player.getAI().setIntentionMoveTo(buffer);}
        return Request.WAIT;
      }
      int before=npcRemaining();((OlympiadManager)buffer).onBypassFeedback(player,"OlyBuff "+index);
      return npcRemaining()<before?Request.DONE:Request.FAILED;
    }
    public List<Action> actions(){return OlympiadPreparationPlan.actions(player);}
    private Creature target(Action a){return a.kind()==Kind.BUFF_PET?player.getSummon():player;}
    public boolean covered(Action a){
      Skill skill=player.getKnownSkill(a.skillId());if(skill==null)return false;
      return switch(a.kind()){
        case SUMMON->player.hasServitor()&&!player.getSummon().isDead();
        case CUBIC->player.getCubics().containsKey(a.extra());
        case CHARGE->player.getCharges()>=a.extra();
        case SOUL->player.getChargedSouls()>=a.extra();
        default->{Creature target=target(a);yield target!=null&&buffCovered(target,skill);}
      };
    }
    public boolean usable(Action a){
      Skill s=player.getKnownSkill(a.skillId());Creature t=target(a);
      if(s==null||t==null||t.isDead()||s.isBlockedInOlympiad()||player.isSkillDisabled(s)||!SupportSupplies.hasReagents(player,s))return false;
      if(player.getCurrentMp()<s.getMpConsume()+s.getMpInitialConsume()+player.getMaxMp()*0.30)return false;
      if(player.getCurrentHp()<=s.getHpConsume()+player.getMaxHp()*0.50)return false;
      return s.checkCondition(player,t,false);
    }
    public Request request(Action a){
      if(!beforeBattle()||!available()||!usable(a))return Request.FAILED;
      if(player.isSitting()){player.standUp();return Request.WAIT;}
      if(player.isMoving())player.stopMove(null);
      player.setTarget(target(a));
      return PhantomCompat.useMagic(player,player.getKnownSkill(a.skillId()),false,false)?Request.ACCEPTED:Request.FAILED;
    }
    public void abortOwnCast(){player.abortCast();}
    private boolean buffCovered(Creature creature,Skill s){
      BuffInfo info=s.getAbnormalType()==AbnormalType.NONE?creature.getEffectList().getBuffInfoBySkillId(s.getId()):creature.getEffectList().getBuffInfoByAbnormalType(s.getAbnormalType());
      return info!=null&&info.isInUse()&&info.getSkill().getAbnormalLevel()>=s.getAbnormalLevel()&&(s.isToggle()||info.getTime()>20);
    }
    void restoreShots(){
      if(!shots&&available()){
        int id=PhantomHunterOutfit.shotId(player);
        if(id>0&&player.getInventory().getInventoryItemCount(id,-1)>0){player.addAutoSoulShot(id);PhantomCompat.rechargeShots(player);}
        shots=true;
      }
    }
    void report(String why){
      if(reported)return;reported=true;
      LOG.info("Olympiad prep "+why+": "+player.getName()+"; game="+game+"; NPC used="+engine.npcUsed()+"; own confirmed="+engine.skillsConfirmed()+"; skipped/failed="+engine.failed()+"; cast timeouts="+engine.castTimeouts());
    }
  }
}
