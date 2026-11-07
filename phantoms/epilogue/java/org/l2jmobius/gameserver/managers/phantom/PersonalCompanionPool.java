package org.l2jmobius.gameserver.managers.phantom;

import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.function.Predicate;import java.util.logging.*;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Separate, persistent characters per summon. The party limit is enforced before allocation. */
public final class PersonalCompanionPool {
 private static final Logger LOG=Logger.getLogger(PersonalCompanionPool.class.getName());
 private static final Store STORE=new Store(Path.of("data/L2HubPersonalInstances.properties"));
 record Instance(String base,int serial,int owner){String key(){return base+"."+serial;}}
 private PersonalCompanionPool(){}
 static String baseKey(PhantomTemplate template){
  var profile=PersonalCompanionNames.named(template.getName());if(profile==null)return null;Instance i=STORE.get(profile.key());return i==null?profile.key():i.base();
 }
 static int ownerId(PhantomTemplate template){var p=PersonalCompanionNames.named(template.getName());Instance i=p==null?null:STORE.get(p.key());return i==null?0:i.owner();}
 static boolean dynamic(PhantomTemplate template){return ownerId(template)>0;}
 static String permission(Player player,PhantomTemplate template,org.l2jmobius.gameserver.entity.clan.Clan bound){
  String reserved=PersonalSavedParty.reservation(player,template);if(reserved!=null)return reserved;int owner=ownerId(template);return owner>0&&owner!=player.getObjectId()&&(bound==null||bound.getId()!=player.getClanId())?"Это личный помощник другого игрока.":null;
 }
 static boolean eligible(Player owner,PhantomTemplate template){
  var companions=PhantomCompanionManager.getInstance();
  if(companions.isPartiedWith(owner,template)||companions.hasHumanOwnerOtherThan(owner,template)||PersonalCompanionClans.access(owner,template)!=null)return false;
  Player actor=PhantomManager.getInstance().getPhantom(template);
  if(actor!=null&&actor.isInParty()&&!actor.getParty().containsPlayer(owner)&&actor.getParty().getMembers().stream().anyMatch(p->!PhantomManager.getInstance().isPhantom(p)))return false;
  return true;
 }
 static PhantomTemplate acquire(Player owner,String base)throws IOException{
  return acquire(owner,base,t->eligible(owner,t),name->PhantomData.getInstance().getTemplate(name)==null&&!CharInfoTable.getInstance().doesCharNameExist(name));
 }
 static synchronized PhantomTemplate acquire(Player owner,String base,Predicate<PhantomTemplate> eligible,Predicate<String> nameAvailable)throws IOException{
  PhantomData data=PhantomData.getInstance();PhantomTemplate source=data.getTemplate(PersonalCompanionNames.name(base));if(source==null||!PersonalCompanions.personal(source))throw new IllegalArgumentException("Unknown personal class: "+base);
  for(PhantomTemplate t:data.getCompanions())if(base.equals(baseKey(t))&&eligible.test(t))return t;
  int serial=2;for(Instance i:STORE.records())if(i.base().equals(base))serial=Math.max(serial,i.serial()+1);
  while(serial<Integer.MAX_VALUE){
   var profile=PersonalCompanionNames.instance(base,serial);
   if(!nameAvailable.test(profile.name())){serial++;continue;}
   Instance instance=new Instance(base,serial,owner.getObjectId());STORE.put(instance);return register(data,source,instance);
  }
  throw new IOException("No available companion names");
 }
 static synchronized void restore(PhantomData data){
  for(Instance instance:STORE.records()){
   try{PhantomTemplate source=data.getTemplate(PersonalCompanionNames.name(instance.base()));if(source==null)throw new IllegalArgumentException("Missing base profile "+instance.base());register(data,source,instance);}
   catch(RuntimeException e){LOG.log(Level.WARNING,"Could not restore personal instance "+instance.key()+"; identity retained",e);}
  }
 }
 static PhantomTemplate copy(PhantomTemplate base,String name){
  int[] classes=java.util.stream.IntStream.of(base.getClassId(1),base.getClassId(20),base.getClassId(40),base.getClassId(76)).distinct().toArray();
  PhantomTemplate next=new PhantomTemplate(name,base.getRole(),base.isFemale(),base.getFace(),base.getHairStyle(),base.getHairColor(),classes,base.getHome(),base.getAllGear(),name,"companion",base.getSocial());
  next.setTemper(base.getTemper());next.setRisk(base.getRisk());return next;
 }
 private static PhantomTemplate register(PhantomData data,PhantomTemplate source,Instance instance){
  var profile=PersonalCompanionNames.instance(instance.base(),instance.serial());
  if(data.getTemplate(profile.name())!=null)throw new IllegalArgumentException("Instance name collision: "+profile.name());
  PhantomTemplate copy=copy(source,profile.name());PersonalCompanionNames.registerInstance(profile);data.registerPersonal(copy);return copy;
 }
 static final class Store {
  private final Path path;private final Map<String,Instance> instances=new LinkedHashMap<>();
  Store(Path path){
   this.path=path;if(!Files.exists(path))return;Properties values=new Properties();
   try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){values.load(reader);}catch(IOException e){throw new IllegalStateException("Cannot read personal instances: "+path,e);}
   for(String key:values.stringPropertyNames())try{
    int at=key.lastIndexOf('.');String base=key.substring(0,at);int serial=Integer.parseInt(key.substring(at+1)),owner=Integer.parseInt(values.getProperty(key));
    if(base.isBlank()||serial<2||owner<=0)throw new IllegalArgumentException();instances.put(key,new Instance(base,serial,owner));
   }catch(RuntimeException e){throw new IllegalStateException("Invalid personal instance record: "+key,e);}
  }
  synchronized List<Instance> records(){return List.copyOf(instances.values());}
  synchronized Instance get(String key){return instances.get(key);}
  synchronized void put(Instance instance)throws IOException{
   if(instances.containsKey(instance.key()))throw new IOException("Instance already exists: "+instance.key());Properties next=new Properties();for(Instance old:instances.values())next.setProperty(old.key(),Integer.toString(old.owner()));next.setProperty(instance.key(),Integer.toString(instance.owner()));
   Files.createDirectories(path.toAbsolutePath().getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");try(var writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){next.store(writer,"Persistent personal companion instances; retain across updates");}
   try{Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
   instances.put(instance.key(),instance);
  }
 }
}
