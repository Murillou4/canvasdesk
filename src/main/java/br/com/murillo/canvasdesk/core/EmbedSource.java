package br.com.murillo.canvasdesk.core;

import java.net.URI;
import java.util.Locale;

public final class EmbedSource {
    private EmbedSource() {}
    public static String validate(String value){
        if(value==null||value.length()>4096)throw new IllegalArgumentException("Endereço muito longo.");
        try{URI uri=URI.create(value.strip());String scheme=uri.getScheme();
            if(scheme==null||!(scheme.equalsIgnoreCase("https")||scheme.equalsIgnoreCase("http"))||uri.getHost()==null||uri.getUserInfo()!=null)
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        }catch(RuntimeException e){throw new IllegalArgumentException("Use um endereço HTTP ou HTTPS completo, sem credenciais.");}
    }
    public static boolean isolated(String value){try{validate(value);return !value.strip().matches("(?s).*\\s.*");}catch(RuntimeException e){return false;}}
    public static String youtubeId(String value){
        URI uri=URI.create(validate(value));String host=uri.getHost().toLowerCase(Locale.ROOT),path=uri.getPath();
        if(host.equals("youtu.be")){if(path.length()<2)return "";String id=path.substring(1).split("/")[0];return id.matches("[A-Za-z0-9_-]{11}")?id:"";}
        if(host.equals("youtube.com")||host.endsWith(".youtube.com")||host.equals("youtube-nocookie.com")||host.endsWith(".youtube-nocookie.com")){
            if(path.startsWith("/embed/")||path.startsWith("/shorts/")){String[] parts=path.split("/");if(parts.length>2&&parts[2].matches("[A-Za-z0-9_-]{11}"))return parts[2];}
            if(uri.getRawQuery()!=null)for(String entry:uri.getRawQuery().split("&"))if(entry.startsWith("v=")&&entry.substring(2).matches("[A-Za-z0-9_-]{11}"))return entry.substring(2);
        }return "";
    }
    public static boolean directVideo(String url){String path=URI.create(validate(url)).getPath().toLowerCase(Locale.ROOT);return path.endsWith(".webm")||path.endsWith(".mp4")||path.endsWith(".ogv")||path.endsWith(".ogg");}
}
