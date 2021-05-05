import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;

public class NTManager{
  private Thread ntThread; 
  public NetworkTable table;
  public NetworkTable infoTable;
  public NetworkTableEntry targets;
  public NetworkTableEntry hasTarget;
  public NetworkTableEntry closestTarget;

  
  public NTManager(boolean server,int team) {
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
    }
    table = ntinst.getTable("ball table");
    infoTable = table.getSubTable("info");
    targets = infoTable.getEntry("targets");
    hasTarget = infoTable.getEntry("hasTarget");
    closestTarget = infoTable.getEntry("closestTarget");

    Runnable updateTables = () -> {
        //get variables 
        //put into networktables
    };

    ntThread = new Thread(updateTables, "ntThread");

  }
}
