import java.sql.DriverManager;
public final class GuardSelfCheck {
 public static void main(String[]args)throws Exception{
  try{DriverManager.getConnection("jdbc:guard-must-block:");throw new AssertionError("NoWorld missing");}
  catch(AssertionError error){if(!error.getMessage().startsWith("Blocked world/JDBC"))throw error;System.out.println("PASS GuardSelfCheck real DriverManager boundary blocked");}
 }
}
