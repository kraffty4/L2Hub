package org.l2jmobius.gameserver.managers.phantom;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import org.w3c.dom.*;

/** Names are ordinary server settings. Stable XML profile keys and persisted character IDs survive renaming. */
public final class PersonalCompanionNames {
 private static final Logger LOG=Logger.getLogger(PersonalCompanionNames.class.getName());
 static final Path CONFIG=Path.of("config/Custom/PersonalCompanionNames.ini");
 static final Path STATE=Path.of("data/L2HubPersonalCompanions.properties");
 private static final Map<String,String> SETTINGS=readSettings(CONFIG);
 private static volatile Map<String,Profile> PROFILES=Map.of();
 private static volatile Map<String,String> ALL_NAMES=Map.of();
 static final Map<String,String> RENAMES=new LinkedHashMap<>();
 private static final IdentityStore IDENTITIES=new IdentityStore(STATE);
 record Profile(String key,String name,List<String> legacyNames,List<String> aliases){}
 record Resolution(int charId,boolean rename,boolean conflict){}
 private PersonalCompanionNames(){}
 static boolean validName(String name){return name!=null&&name.matches("[A-Za-z0-9]{1,16}");}
 static Map<String,String> readSettings(Path path){
  Properties values=new Properties();
  if(Files.exists(path))try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){values.load(reader);}catch(IOException e){throw new IllegalStateException("Cannot read personal name settings: "+path,e);}
  Map<String,String> result=new LinkedHashMap<>();
  for(String source:values.stringPropertyNames()){
   String key=source.strip().toLowerCase(Locale.ROOT),name=values.getProperty(source).strip();
   if(!key.matches("[a-z0-9_.-]+"))throw new IllegalArgumentException("Invalid personal profile key: "+source);
   if(!name.isEmpty()&&!validName(name))throw new IllegalArgumentException("Invalid personal name for "+key+": use 1-16 Latin letters/digits");
   if(result.putIfAbsent(key,name)!=null)throw new IllegalArgumentException("Repeated personal profile key: "+key);
  }
  return Collections.unmodifiableMap(result);
 }
 /** Validate the entire document before changing anything; normal hunters and all gear remain intact. */
 static synchronized void prepare(Document document){prepare(document,SETTINGS);}
 static synchronized void prepare(Document document,Map<String,String> settings){
  NodeList nodes=document.getElementsByTagName("phantom");Map<String,Profile> profiles=new LinkedHashMap<>();Map<String,String> allNames=new HashMap<>();Map<Element,Profile> edits=new LinkedHashMap<>();
  for(int i=0;i<nodes.getLength();i++){
   Element node=(Element)nodes.item(i);String raw=node.getAttribute("name"),key=node.getAttribute("profile").strip().toLowerCase(Locale.ROOT);String desired=raw;
   if(!key.isEmpty()){
    if("hunter".equalsIgnoreCase(node.getAttribute("kind"))||"reaper".equalsIgnoreCase(node.getAttribute("kind")))throw new IllegalArgumentException("Personal profile key on non-personal template: "+key);
    if(!key.matches("[a-z0-9_.-]+"))throw new IllegalArgumentException("Invalid XML personal profile key: "+key);
    desired=settings.getOrDefault(key,raw);if(desired.isEmpty())desired=raw;
    if(!validName(desired))throw new IllegalArgumentException("Invalid personal name for "+key);
    LinkedHashSet<String> legacy=new LinkedHashSet<>();for(String old:node.getAttribute("legacyNames").split(";"))if(!old.isBlank())legacy.add(old.strip());legacy.add(raw);
    LinkedHashSet<String> aliases=new LinkedHashSet<>(legacy);for(String alias:node.getAttribute("aliases").split(";"))if(!alias.isBlank())aliases.add(alias.strip());aliases.add(desired);aliases.add(key);
    Profile p=new Profile(key,desired,List.copyOf(legacy),List.copyOf(aliases));if(profiles.putIfAbsent(key,p)!=null)throw new IllegalArgumentException("Duplicate personal profile key: "+key);edits.put(node,p);
   }
   if(allNames.putIfAbsent(desired.toLowerCase(Locale.ROOT),raw)!=null)throw new IllegalArgumentException("Duplicate phantom name: "+desired);
  }
  for(String key:settings.keySet())if(!profiles.containsKey(key))throw new IllegalArgumentException("Unknown personal profile in settings: "+key);
  for(var edit:edits.entrySet()){edit.getKey().setAttribute("name",edit.getValue().name());edit.getKey().setAttribute("aliases",String.join(";",edit.getValue().aliases()));}
  PROFILES=Collections.unmodifiableMap(profiles);Map<String,String> canonicalNames=new HashMap<>();for(int i=0;i<nodes.getLength();i++){Element node=(Element)nodes.item(i);canonicalNames.put(node.getAttribute("name").toLowerCase(Locale.ROOT),node.getAttribute("name"));}ALL_NAMES=Map.copyOf(canonicalNames);RENAMES.clear();for(Profile p:profiles.values())for(String old:p.legacyNames())if(!old.equalsIgnoreCase(p.name()))RENAMES.put(old,p.name());
 }
 static Profile profile(String name){
  if(name==null)return null;for(Profile p:PROFILES.values())if(p.key().equalsIgnoreCase(name)||p.aliases().stream().anyMatch(x->x.equalsIgnoreCase(name)))return p;return null;
 }
 static String name(String key){Profile p=PROFILES.get(key);return p==null?key:p.name();}
 static String canonical(String name){if(name==null)return null;String exact=ALL_NAMES.get(name.toLowerCase(Locale.ROOT));if(exact!=null)return exact;for(Profile p:PROFILES.values())if(name.equalsIgnoreCase(IDENTITIES.name(p.key())))return p.name();Profile p=profile(name);return p==null?name:p.name();}
 static Profile named(String name){if(name==null)return null;for(Profile p:PROFILES.values())if(p.name().equalsIgnoreCase(name))return p;return null;}
 static String previous(String name){Profile p=profile(name);return p==null?null:p.legacyNames().stream().filter(x->!x.equalsIgnoreCase(p.name())).findFirst().orElse(null);}
 static List<String> candidates(String name){
  Profile p=named(name);if(p==null)return List.of();LinkedHashSet<String> names=new LinkedHashSet<>();String stored=IDENTITIES.name(p.key());if(stored!=null)names.add(stored);names.addAll(p.legacyNames());names.add(p.name());return List.copyOf(names);
 }
 static int characterId(String name){Profile p=named(name);return p==null?0:IDENTITIES.id(p.key());}
 static boolean remember(String name,int id,String storedName){Profile p=named(name);if(p==null)return true;try{IDENTITIES.remember(p.key(),id,storedName);return true;}catch(IOException e){LOG.log(Level.WARNING,"Could not preserve companion identity; spawn cancelled",e);return false;}}
 static String numbered(String name,int serial){String suffix=Integer.toString(serial);return name.substring(0,Math.min(name.length(),16-suffix.length()))+suffix;}
 static Profile instance(String baseKey,int serial){
  Profile base=PROFILES.get(baseKey);if(base==null)throw new IllegalArgumentException("Unknown personal base profile: "+baseKey);
  String key=baseKey+"."+serial,name=PersonalNamePool.name(key,ALL_NAMES.keySet()),raw=numbered(base.legacyNames().getLast(),serial),old=numbered(base.name(),serial);
  List<String> legacy=raw.equalsIgnoreCase(old)?List.of(raw):List.of(raw,old);
  List<String> aliases=new ArrayList<>(legacy);aliases.add(name);aliases.add(key);
  return new Profile(key,name,legacy,List.copyOf(aliases));
 }
 static synchronized void registerInstance(Profile profile){
  Profile existing=PROFILES.get(profile.key());if(existing!=null&&existing.equals(profile))return;
  String occupied=ALL_NAMES.get(profile.name().toLowerCase(Locale.ROOT));if(occupied!=null)throw new IllegalArgumentException("Duplicate personal instance name: "+profile.name());
  Map<String,Profile> profiles=new LinkedHashMap<>(PROFILES);profiles.put(profile.key(),profile);PROFILES=Collections.unmodifiableMap(profiles);
  Map<String,String> names=new HashMap<>(ALL_NAMES);names.put(profile.name().toLowerCase(Locale.ROOT),profile.name());ALL_NAMES=Map.copyOf(names);
 }
 static Resolution resolve(int currentId,int previousId,boolean occupied){
  if(currentId>0&&previousId>0&&currentId!=previousId)return new Resolution(0,false,true);
  if(currentId>0)return new Resolution(currentId,false,false);
  if(previousId>0)return occupied?new Resolution(0,false,true):new Resolution(previousId,true,false);
  return new Resolution(0,false,occupied);
 }
 /** Runtime identity data, never a module configuration override. Write atomically, retain unknown profiles. */
 static final class IdentityStore {
  private final Path path;private final Properties values=new Properties();
  IdentityStore(Path path){
   this.path=path;if(Files.exists(path))try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){values.load(reader);}catch(IOException e){throw new IllegalStateException("Cannot read companion identities: "+path,e);}
   for(String key:values.stringPropertyNames())if(key.endsWith(".id"))try{if(Integer.parseInt(values.getProperty(key))<=0)throw new NumberFormatException();}catch(NumberFormatException e){throw new IllegalStateException("Invalid companion identity: "+key,e);}
  }
  synchronized int id(String key){return Integer.parseInt(values.getProperty(key+".id","0"));}
  synchronized String name(String key){return values.getProperty(key+".name");}
  synchronized void remember(String key,int id,String name)throws IOException{
   if(id<=0||!validName(name))throw new IllegalArgumentException("Invalid companion identity");
   if(id(key)>0&&id(key)!=id)throw new IOException("Companion profile already belongs to another character ID: "+key);
   if(id(key)==id&&Objects.equals(name(key),name))return;
   Properties next=new Properties();next.putAll(values);next.setProperty(key+".id",Integer.toString(id));next.setProperty(key+".name",name);
   Files.createDirectories(path.toAbsolutePath().getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");
   try(var writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){next.store(writer,"Stable personal companion identities; retain across updates");}
   try{Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
   values.clear();values.putAll(next);
  }
 }
}
