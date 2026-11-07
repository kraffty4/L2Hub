import java.nio.file.*;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
/** Не инициализирует production-классы. Код и ресурс каждого класса обязаны быть из finalJar. */
public final class ArtifactProvenanceCheck {
 public static void main(String[]args)throws Exception{
  Path jar=Path.of(args[0]).toRealPath();int count=0;
  for(String row:Files.readAllLines(Path.of(args[1]))){
   if(row.isBlank())continue;String[] fields=row.split("\t",2);String name=fields[0],wanted=fields[1];Class<?> type=Class.forName(name,false,ClassLoader.getSystemClassLoader());
   var domain=type.getProtectionDomain();if(domain==null||domain.getCodeSource()==null)throw new AssertionError("No CodeSource "+name);
   URI code=domain.getCodeSource().getLocation().toURI();if(!Path.of(code).toRealPath().equals(jar))throw new AssertionError("Shadow class "+name+" from "+code);
   String resource="/"+name.replace('.','/')+".class";var url=type.getResource(resource);if(url==null||!url.getProtocol().equals("jar"))throw new AssertionError("Non-JAR resource "+name+" "+url);
   var connection=(java.net.JarURLConnection)url.openConnection();connection.setUseCaches(false);
   if(!Path.of(connection.getJarFileURL().toURI()).toRealPath().equals(jar))throw new AssertionError("Shadow resource "+name+" "+url);
   String actual;try(var input=url.openStream()){actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));}
   if(!actual.equals(wanted))throw new AssertionError("Resource SHA mismatch "+name);
   System.out.println("CLASS\t"+name+"\t"+code+"\t"+url+"\t"+actual);count++;
  }
  if(count==0)throw new AssertionError("Empty provenance set");System.out.println("PASS ArtifactProvenanceCheck classes="+count+" exact-final-JAR CodeSource/resources/SHA");
 }
}
