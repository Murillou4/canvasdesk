package br.com.murillo.canvasdesk.browser;

import com.google.gson.*;
import me.friwi.jcefmaven.*;
import me.friwi.jcefmaven.impl.step.extract.TarGzExtractor;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Pinned publisher digests, bounded download and a cross-process installation lock. */
final class NativeRuntime {
    static void prepare(Path cache,CefAppBuilder builder,Consumer<String> status)throws Exception {
        String platform=EnumPlatform.getCurrentPlatform().getIdentifier();
        JsonObject hashes;try(var in=NativeRuntime.class.getResourceAsStream("/browser/native-sha256.json")){hashes=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in))).getAsJsonObject();}
        if(!hashes.has(platform))throw new IOException("Plataforma do navegador indisponível: "+platform);
        String expected=hashes.get(platform).getAsString();long deadline=System.nanoTime()+120_000_000_000L;
        Path lock=cache.resolveSibling(cache.getFileName()+".install-lock");
        try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            FileLock held=null;while(held==null){try{held=channel.tryLock();}catch(OverlappingFileLockException ignored){}if(held==null){status.accept("Aguardando instalação compartilhada…");if(System.nanoTime()>deadline)throw new IOException("Tempo de instalação esgotado.");Thread.sleep(200);}}
            try {
                Path manifest=cache.resolve("canvasdesk-integrity.json");
                if(!verified(cache,manifest,expected)){
                    String tag=CefBuildInfo.fromClasspath().getReleaseTag(),name="jcef-natives-"+platform+"-"+tag+".jar";
                    Path archive=cache.resolve("runtime.jar");
                    if(!Files.isRegularFile(archive)||!sha(archive).equals(expected)){
                        Path partial=cache.resolve("runtime.part");
                        try {
                            URLConnection connection=URI.create("https://github.com/jcefmaven/jcefmaven/releases/download/152.0.6/"+name).toURL().openConnection();connection.setConnectTimeout(15_000);connection.setReadTimeout(15_000);
                            long size=connection.getContentLengthLong(),total=0;int percent=-1;
                            try(var input=connection.getInputStream();var output=Files.newOutputStream(partial)) {byte[] block=new byte[65536];int n;while((n=input.read(block))>=0){if(System.nanoTime()>deadline)throw new IOException("Download excedeu 120 segundos.");output.write(block,0,n);total+=n;int next=size>0?(int)(total*100/size):0;if(next!=percent){percent=next;status.accept("Baixando navegador: "+percent+"%");}}}
                            status.accept("Verificando SHA-256…");if(!sha(partial).equals(expected))throw new IOException("SHA-256 do navegador não corresponde ao fornecedor.");Files.move(partial,archive,StandardCopyOption.REPLACE_EXISTING);
                        }finally{Files.deleteIfExists(partial);}
                    }
                    status.accept("Extraindo navegador verificado…");
                    try(var zip=new ZipFile(archive.toFile())){var entries=zip.entries();boolean extracted=false;while(entries.hasMoreElements()){var entry=entries.nextElement();if(entry.getName().endsWith(".tar.gz")){try(var input=zip.getInputStream(entry)){TarGzExtractor.extractTarGZ(cache.toFile(),input);}extracted=true;break;}}if(!extracted)throw new IOException("Pacote nativo sem runtime.");}
                    Files.writeString(cache.resolve("install.lock"),"");JsonObject files=new JsonObject();
                    try(var paths=Files.walk(cache)){for(Path file:paths.filter(Files::isRegularFile).toList()){if(file.equals(archive)||file.equals(manifest))continue;files.addProperty(cache.relativize(file).toString().replace('\\','/'),sha(file));}}
                    JsonObject record=new JsonObject();record.addProperty("artifactSha256",expected);record.add("files",files);Files.writeString(manifest,new Gson().toJson(record));
                }
                builder.setSkipInstallation(true);
            }finally{held.release();}
        }
    }
    private static boolean verified(Path cache,Path manifest,String expected){
        try{
            JsonObject record=JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            if(!expected.equals(record.get("artifactSha256").getAsString()))return false;
            JsonObject metadata=record.has("metadata")?record.getAsJsonObject("metadata"):new JsonObject();boolean changed=false;
            for(var entry:record.getAsJsonObject("files").entrySet()){
                Path file=cache.resolve(entry.getKey()).normalize();if(!file.startsWith(cache.normalize())||!Files.isRegularFile(file))return false;
                String fingerprint=Files.size(file)+":"+Files.getLastModifiedTime(file).toMillis();
                if(!metadata.has(entry.getKey())||!metadata.get(entry.getKey()).getAsString().equals(fingerprint)){
                    if(!sha(file).equals(entry.getValue().getAsString()))return false;
                    metadata.addProperty(entry.getKey(),fingerprint);changed=true;
                }
            }
            if(changed){record.add("metadata",metadata);Files.writeString(manifest,new Gson().toJson(record));}
            return record.getAsJsonObject("files").size()>5;
        }catch(Exception absent){return false;}
    }
    private static String sha(Path file)throws Exception {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] block=new byte[65536];int n;while((n=input.read(block))>=0)digest.update(block,0,n);}return HexFormat.of().formatHex(digest.digest());}
}
