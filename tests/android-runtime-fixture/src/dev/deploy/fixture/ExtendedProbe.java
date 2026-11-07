package dev.deploy.fixture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.json.JSONObject;

/** Each probe has a real positive disabled baseline; raw native maps remain the control. */
final class ExtendedProbe {
 static boolean rawMarker(Object value) throws IOException {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(value);}
  return new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1).contains("dev.posedmcp.fixture.VisibleMarker");
 }
 static String lookup(String name,ClassLoader loader) {
  try {Class.forName(name,false,loader);return "visible";}catch(ClassNotFoundException absent){return "hidden";}
 }
 static int jars(String maps){return Results.jarCount(maps);}
 static String optionalReadString(Path path) throws Exception {
  try{return (String)Files.class.getMethod("readString",Path.class).invoke(null,path);}
  catch(NoSuchMethodException unsupported){return null;}
 }
 static JSONObject collect(android.content.Context context) throws Exception {
  JSONObject out=new JSONObject();
  Throwable error=new Throwable("fixture");
  StackTraceElement[] cached={new StackTraceElement("dev.posedmcp.fixture.VisibleMarker","capture","VisibleMarker.java",3),
   new StackTraceElement("dev.deploy.fixture.ExtendedProbe","collect","ExtendedProbe.java",1),
   new StackTraceElement("com.android.internal.os.ZygoteInit","main","ZygoteInit.java",42)};
  error.setStackTrace(cached);
  StackTraceElement[] trace=error.getStackTrace();
  out.put("controlledTraceCount",trace.length).put("cachedTraceCount",cached.length).put("controlledRawMarker",rawMarker(trace));
  StackTraceElement[] current=dev.posedmcp.fixture.VisibleMarker.capture(()->Thread.currentThread().getStackTrace());
  out.put("threadRawMarker",rawMarker(current));
  Map<Thread,StackTraceElement[]> all=dev.posedmcp.fixture.VisibleMarker.capture(()->Thread.getAllStackTraces());
  out.put("allThreadsOwnPresent",all.containsKey(Thread.currentThread())).put("allThreadsRawMarker",rawMarker(all.get(Thread.currentThread())));
  ClassLoader loader=context.getClassLoader();
  out.put("forName",lookup("dev.posedmcp.fixture.VisibleMarker",loader))
   .put("forNameArray",lookup("[[Ldev.posedmcp.fixture.VisibleMarker;",loader))
   .put("ordinaryForName",lookup("java.lang.String",loader));
  try {out.put("ordinaryCallerClass",Class.forName("dev.deploy.fixture.Sentinel")!=null);}
  catch(ClassNotFoundException failure){out.put("ordinaryCallerClass",false);}
  String resource="dev/posedmcp/fixture/VisibleMarker.class";
  out.put("classResource",loader.getResource(resource)!=null).put("classResources",loader.getResources(resource).hasMoreElements());
  try(InputStream stream=loader.getResourceAsStream(resource)){out.put("classResourceStream",stream!=null);}
  out.put("ordinaryResource",loader.getResource("fixture/XposedBridge.class")!=null);
  Path maps=Paths.get("/proc/self/maps");
  out.put("nioBytesJar",jars(new String(Files.readAllBytes(maps),StandardCharsets.ISO_8859_1)));
  String nioString=optionalReadString(maps);
  out.put("nioStringSupported",nioString!=null).put("nioStringJar",nioString==null ? -1 : jars(nioString));
  out.put("nioLinesJar",jars(String.join("\n",Files.readAllLines(maps))));
  try(Stream<String> stream=Files.lines(maps)){out.put("nioStreamJar",stream.filter(s->s.contains("XposedBridge.jar")).count());}
  out.put("nioTaskJar",jars(new String(Files.readAllBytes(Paths.get("/proc/self/task/"+android.os.Process.myTid()+"/maps")),StandardCharsets.ISO_8859_1)));
  StringBuilder lines=new StringBuilder();
  try(RandomAccessFile file=new RandomAccessFile("/proc/self/maps","r")){String line;while((line=file.readLine())!=null)lines.append(line).append('\n');}
  out.put("randomAccessJar",jars(lines.toString()));
  File ordinary=new File(context.getFilesDir(),"extended-control.txt");
  String content="1000-2000 r--p /memfd:XposedBridge.jar (deleted)\nordinary\n";
  try {
   Files.write(ordinary.toPath(),content.getBytes(StandardCharsets.UTF_8));
   out.put("ordinaryNio",new String(Files.readAllBytes(ordinary.toPath()),StandardCharsets.UTF_8).equals(content));
   try(RandomAccessFile file=new RandomAccessFile(ordinary,"r")){out.put("ordinaryRandomAccess",file.readLine().contains("XposedBridge.jar") && file.readLine().equals("ordinary"));}
  } finally {ordinary.delete();}
  return out;
 }
}
