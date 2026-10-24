package dev.deploy.fixture;
import java.io.*; import java.lang.reflect.*; import org.json.*;
public final class Results {
 static String providerValue,applicationValue; static long providerTime;
 static { System.loadLibrary("runtimefixture"); }
 public static native int resolve(String host,boolean numericOnly);
 public static native String rawMaps();
 public static JSONObject collect(android.content.Context c) throws Exception {
 JSONObject r=new JSONObject(); r.put("pid",android.os.Process.myPid()).put("provider",providerValue).put("application",applicationValue).put("providerTime",providerTime);
 r.put("hiddenStack",new StackTraceElement("dev.posedmcp.xposed.AppHost","install","AppHost.java",1).getClassName());
 r.put("ownStack",new StackTraceElement("dev.deploy.fixture.Results","collect","Results.java",1).getClassName());
 try { c.getClassLoader().loadClass("dev.posedmcp.fixture.VisibleMarker"); r.put("classProbe","visible"); } catch (ClassNotFoundException e) { r.put("classProbe","hidden"); }
 r.put("javaNativeBit",Modifier.isNative(StackTraceElement.class.getDeclaredMethod("getClassName").getModifiers()));
 r.put("sentinelNativeBit",Modifier.isNative(Sentinel.class.getDeclaredMethod("answer").getModifiers()));
 r.put("jniNativeBit",Modifier.isNative(Results.class.getDeclaredMethod("resolve",String.class,boolean.class).getModifiers()));
 StringBuilder maps=new StringBuilder(); try(BufferedReader b=new BufferedReader(new FileReader("/proc/self/maps"))) { String line; while((line=b.readLine())!=null) maps.append(line).append('\n'); }
 r.put("javaModuleMaps",count(maps.toString())).put("nativeModuleMaps",count(rawMaps()));
 try(BufferedReader b=new BufferedReader(new StringReader("libposednative.so\nkeep\n"))) { r.put("ordinaryReader",b.readLine()+"|"+b.readLine()+"|"+b.readLine()); }
 r.put("dnsExact",resolve("example.com",false)).put("dnsWildcard",resolve("ad.ads.example.com",false)).put("dnsNumeric",resolve("127.0.0.1",true)).put("dnsLocalhost",resolve("localhost",false));
 return r; }
 static int count(String s) { int n=0; for(String l:s.split("\n")) if(l.contains("dev.posedmcp-")||l.contains("libposednative.so")||l.toLowerCase().contains("lsposed")) n++; return n; }
}