/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoSource;
import edu.wpi.cscore.CvSink;
import edu.wpi.cscore.CvSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.EntryListenerFlags;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionThread;
import edu.wpi.first.wpilibj.geometry.Pose2d;
import edu.wpi.first.wpilibj.geometry.Rotation2d;
import edu.wpi.first.wpilibj.geometry.Translation2d;

import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.Scalar;
import org.opencv.features2d.Features2d;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
       "switched cameras": [
           {
               "name": <virtual camera name>
               "key": <network table key used for selection>
               // if NT value is a string, it's treated as a name
               // if NT value is a double, it's treated as an integer index
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  @SuppressWarnings("MemberName")
  public static class SwitchedCameraConfig {
    public String name;
    public String key;
  };

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();
  public static List<SwitchedCameraConfig> switchedCameraConfigs = new ArrayList<>();
  public static List<VideoSource> cameras = new ArrayList<>();

  public static CvSink inputSink;
  public static CvSource outputSource;

  public static NetworkTable table;
  public static int width = 320;
  public static int height = 240;
  public static double horizontalFOV = 53;
  public static double verticalFOV = 32;
  public static double horizontalOffset = 0; //meters from front of robot to camera
  public static double verticalOffset = 0.48 - 0.0889; //height of the camera minus the radius of the ball
  public static double angleOffset = 0; //degree angle of camera from horizontal


  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read single switched camera configuration.
   */
  public static boolean readSwitchedCameraConfig(JsonObject config) {
    SwitchedCameraConfig cam = new SwitchedCameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read switched camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement keyElement = config.get("key");
    if (keyElement == null) {
      parseError("switched camera '" + cam.name + "': could not read key");
      return false;
    }
    cam.key = keyElement.getAsString();

    switchedCameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    if (obj.has("switched cameras")) {
      JsonArray switchedCameras = obj.get("switched cameras").getAsJsonArray();
      for (JsonElement camera : switchedCameras) {
        if (!readSwitchedCameraConfig(camera.getAsJsonObject())) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Start running the switched camera.
   */
  public static MjpegServer startSwitchedCamera(SwitchedCameraConfig config) {
    System.out.println("Starting switched camera '" + config.name + "' on " + config.key);
    MjpegServer server = CameraServer.getInstance().addSwitchedCamera(config.name);

    NetworkTableInstance.getDefault()
        .getEntry(config.key)
        .addListener(event -> {
              if (event.value.isDouble()) {
                int i = (int) event.value.getDouble();
                if (i >= 0 && i < cameras.size()) {
                  server.setSource(cameras.get(i));
                }
              } else if (event.value.isString()) {
                String str = event.value.getString();
                for (int i = 0; i < cameraConfigs.size(); i++) {
                  if (str.equals(cameraConfigs.get(i).name)) {
                    server.setSource(cameras.get(i));
                    break;
                  }
                }
              }
            },
            EntryListenerFlags.kImmediate | EntryListenerFlags.kNew | EntryListenerFlags.kUpdate);
    return server;
  }

  /**
   * Example pipeline.
   */
  // public static class MyPipeline implements VisionPipeline {
  //   public int val;

  //   @Override
  //   public void process(Mat mat) {
  //     val += 1;
  //   }
  // }

  /**
   * calculates straight line distance in 3d space
   * @param yOffset degrees offset in y direction
   * @param xOffset degrees offset in x direction
   * @return distance
   */
  public static double distance(double yOffset, double xOffset) {
    return forwardDistance(yOffset) / Math.cos(Math.toRadians(xOffset));
  }

  public static double horizontalDistance(double forwardDist, double xOffset) {
    return forwardDist * Math.tan(Math.toRadians(xOffset));
  }

  public static double forwardDistance(double yOffset) {
    return Math.abs(verticalOffset / Math.tan(Math.toRadians(angleOffset + yOffset)))- horizontalOffset;
  }

  public static double calculateAngle(double pixelOffset, double fov, double width) {
    return pixelOffset * fov / width;
  }

  public static void annotateImage(MatOfKeyPoint keypoints) {
    Mat inputMat = new Mat();
    Mat outputMat = new Mat();
    inputSink.setSource(cameras.get(0));
    inputSink.grabFrame(inputMat);
    Features2d.drawKeypoints(inputMat, keypoints, outputMat, new Scalar(0.0, 255, 0.0), Features2d.DRAW_RICH_KEYPOINTS);
    outputSource.putFrame(outputMat);
  }

  public static void initCameraServer() {
    System.out.println("setting up output server");
    inputSink = new CvSink("sink");
    inputSink.setSource(cameras.get(0));
    outputSource = CameraServer.getInstance().putVideo("annotated", width, height);
  }

  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }


    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
   NTManager ntManager = new NTManager(server, team);

    // start cameras
    for (CameraConfig config : cameraConfigs) {
      cameras.add(startCamera(config));
    }


    // start switched cameras
    for (SwitchedCameraConfig config : switchedCameraConfigs) {
      startSwitchedCamera(config);
    }

    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {    
      initCameraServer();
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new BallPipeline(), pipeline -> {
                annotateImage(pipeline.findBlobsOutput());
                int i = 0;
                double dist = 100.0;
                int closest = 100;
                table = ntinst.getTable("ball table");
                table.getSubTable("info");
                for (KeyPoint point : pipeline.findBlobsOutput().toList()) {
                  i++;
                  double xDeg = calculateAngle(point.pt.x - width / 2, horizontalFOV, width);
                  double yDeg = calculateAngle(-(point.pt.y - height / 2), horizontalFOV, height);
                  double x = forwardDistance(yDeg); //forward is x and sideways is y i think
                  double y = horizontalDistance(x, xDeg);
                  if (dist >= distance(yDeg, xDeg)) {
                    dist = distance(yDeg, xDeg);
                    closest = i;
                  }
                  //sends a double array for each ball that goes xDeg, yDeg, distance, x, y, size
                  table.getEntry("ball " + i).setDoubleArray(new double[]{xDeg, yDeg, distance(yDeg, xDeg), x, y, point.size});
                }
                if (i < table.getSubTable("info").getEntry("targets").getDouble(0.0)) {
                  for (i++ ; i <= table.getEntry("targets").getDouble(0.0) ; i++) {
                    table.delete("ball " + i);
                  }
                }

                table.getSubTable("info").getEntry("targets").setDouble(table.getKeys().size());
                table.getSubTable("info").getEntry("has target").setBoolean(table.getKeys().size() != 0.0);
                if (table.getKeys().size() != 0.0 && closest != 100) {
                  table.getSubTable("info").getEntry("closest target").setString("ball " + closest);
                }
        // do something with pipeline results
      });
      /* something like this for GRIP:
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new GripPipeline(), pipeline -> {
        ...
      });
       */
      visionThread.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }
}
