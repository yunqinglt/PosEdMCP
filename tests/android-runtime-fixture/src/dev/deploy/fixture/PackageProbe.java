package dev.deploy.fixture;

import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import java.io.*;
import java.util.*;
import org.json.*;

final class PackageProbe {
    static final String[] SELECTED = {"dev.posedmcp","dev.deploy","dev.display","org.lsposed.manager"};
    static JSONObject collect(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        JSONObject out = new JSONObject();
        out.put("managerAvailable",pm!=null).put("queryAllGranted",context.checkSelfPermission(
                "android.permission.QUERY_ALL_PACKAGES")==PackageManager.PERMISSION_GRANTED);
        out.put("sameManagerInstance",pm==context.getPackageManager());
        List<PackageInfo> packages=pm.getInstalledPackages(0);
        List<ApplicationInfo> applications=pm.getInstalledApplications(0);
        List<ResolveInfo> activities=pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN),PackageManager.MATCH_ALL);
        out.put("packages",selected(packages.stream().map(p->p.packageName).toArray(String[]::new)));
        out.put("applications",selected(applications.stream().map(p->p.packageName).toArray(String[]::new)));
        out.put("activities",selected(activities.stream().map(p->p.activityInfo.packageName).toArray(String[]::new)));
        out.put("packageCount",packages.size()).put("applicationCount",applications.size());
        out.put("ownVisible",packages.stream().anyMatch(p->p.packageName.equals(context.getPackageName())));
        out.put("androidVisible",packages.stream().anyMatch(p->p.packageName.equals("android")));
        for(String name:SELECTED) {
            JSONObject probe=new JSONObject();
            try {pm.getApplicationInfo(name,0);probe.put("application",true);}catch(PackageManager.NameNotFoundException e){probe.put("application",false);}
            try {pm.getPackageInfo(name,0);probe.put("package",true);}catch(PackageManager.NameNotFoundException e){probe.put("package",false);}
            try {probe.put("uid",pm.getPackageUid(name,0));}catch(PackageManager.NameNotFoundException e){probe.put("uid",-1);}
            try {pm.getInstallSourceInfo(name);probe.put("source",true);}catch(PackageManager.NameNotFoundException e){probe.put("source",false);}
            probe.put("launch",pm.getLaunchIntentForPackage(name)!=null);
            out.put(name,probe);
        }
        int uid=context instanceof android.app.Activity ? ((android.app.Activity)context).getIntent().getIntExtra("hidden_uid",-1) : -1;
        if(uid>=0)out.put("hiddenUidNames",selected(pm.getPackagesForUid(uid)));
        out.put("ownUidVisible",Arrays.asList(pm.getPackagesForUid(android.os.Process.myUid())).contains(context.getPackageName()));
        out.put("binderProxy",binderProxy(context.getPackageName()));
        out.put("runtimeCommand",command(Runtime.getRuntime().exec("pm list packages")));
        out.put("builderCommand",command(new ProcessBuilder("cmd","package","list","packages","-f","-U").start()));
        return out;
    }
    static JSONObject binderProxy(String own) throws Exception {
        Object proxy=Class.forName("android.app.ActivityThread").getDeclaredMethod("getPackageManager").invoke(null);
        for(java.lang.reflect.Method m:proxy.getClass().getDeclaredMethods())if(m.getName().equals("getPackageInfo")
                &&m.getParameterTypes().length==3&&m.getParameterTypes()[0]==String.class) {
            m.setAccessible(true);Object flags=m.getParameterTypes()[1]==long.class?Long.valueOf(0):Integer.valueOf(0);
            // Avoid conditional numeric promotion producing Long for an int overload.
            if(m.getParameterTypes()[1]==int.class) flags=Integer.valueOf(0);
            return new JSONObject().put("hiddenAbsent",m.invoke(proxy,"dev.posedmcp",flags,0)==null)
                    .put("ownPresent",m.invoke(proxy,own,flags,0)!=null);
        }
        return new JSONObject().put("unavailable",true);
    }
    static JSONArray selected(String[] packages) {
        JSONArray found=new JSONArray(); if(packages!=null)for(String name:SELECTED)
            if(Arrays.asList(packages).contains(name))found.put(name);
        return found;
    }
    static JSONObject command(Process child) throws Exception {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096];int n;
            try(InputStream stream=child.getInputStream()){while((n=stream.read(buffer))!=-1&&bytes.size()<1048576)bytes.write(buffer,0,n);}
            String text=bytes.toString("UTF-8");List<String> names=new ArrayList<>();
            for(String line:text.split("\n"))if(line.startsWith("package:")) {
                String token=line.substring(8).trim().split("\\s+",2)[0];int equals=token.lastIndexOf('=');
                names.add(equals<0?token:token.substring(equals+1));
            }
            return new JSONObject().put("exit",child.waitFor()).put("selected",selected(names.toArray(new String[0])))
                    .put("ownVisible",names.contains("dev.deploy.fixture")).put("count",names.size());
        }finally{child.destroy();}
    }
}
