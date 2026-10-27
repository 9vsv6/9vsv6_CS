import com.harbor.capstan.EpisodeItem;
import com.harbor.capstan.ExtensionLoader;
import com.harbor.capstan.LinkSet;
import com.harbor.capstan.LoadedExtension;
import com.harbor.capstan.MediaItem;
import com.harbor.capstan.Provider;
import com.harbor.capstan.StreamLink;

import java.io.File;
import java.util.List;

/** Runs a .cs3 through Harbor's own capstan runtime, exactly like the app does. */
public class Harness {
    public static void main(String[] args) {
        String cs3 = args[0];
        String url = args[1];
        Integer season = args.length > 2 && !args[2].equals("null") ? Integer.valueOf(args[2]) : null;
        Integer episode = args.length > 3 && !args[3].equals("null") ? Integer.valueOf(args[3]) : null;

        String dexTools = System.getProperty("capstan.dextools");
        String cache = System.getProperty("capstan.cache");
        ExtensionLoader configured = null;
        if (dexTools != null) {
            configured = new ExtensionLoader(new com.harbor.capstan.LoaderConfig(
                    new File(cache != null ? cache : "build/capstan-cache"),
                    new File(dexTools),
                    60000L));
        }

        try (ExtensionLoader loader = configured != null ? configured : new ExtensionLoader()) {
            LoadedExtension ext = loader.load(new File(cs3));
            System.out.println("EXT name=" + ext.getName() + " v" + ext.getVersion()
                    + " providers=" + ext.getProviderNames() + " unavailable=" + ext.getUnavailable());

            for (Provider provider : ext.getProviders()) {
                System.out.println("PROVIDER " + provider.getName() + " lang=" + provider.getLang()
                        + " url=" + provider.getMainUrl());
                try {
                    MediaItem item = provider.load(url);
                    if (item == null) {
                        System.out.println("LOAD returned null");
                        continue;
                    }
                    List<EpisodeItem> episodes = item.getEpisodes();
                    System.out.println("LOAD ok name=" + item.getName() + " type=" + item.getType()
                            + " year=" + item.getYear() + " episodes=" + (episodes == null ? 0 : episodes.size()));

                    String data = null;
                    if (season == null) {
                        data = item.getPlayableData();
                        System.out.println("PLAYABLE " + data);
                    } else if (episodes != null) {
                        for (EpisodeItem ep : episodes) {
                            if (season.equals(ep.getSeason()) && episode.equals(ep.getEpisode())) {
                                data = ep.getData();
                                break;
                            }
                        }
                        System.out.println("EPISODE data=" + data);
                    }
                    if (data == null) {
                        System.out.println("NO DATA to resolve");
                        continue;
                    }

                    long start = System.currentTimeMillis();
                    LinkSet set = provider.loadLinks(data, false);
                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println("LINKS handled=" + set.getHandled() + " count=" + set.getLinks().size()
                            + " subtitles=" + set.getSubtitles().size() + " in " + elapsed + "ms");
                    for (StreamLink link : set.getLinks()) {
                        System.out.println("  [" + link.getSource() + "] " + link.getName()
                                + " q=" + link.getQuality() + " type=" + link.getType() + " -> " + link.getUrl());
                    }
                } catch (Throwable t) {
                    System.out.println("FAILED " + t.getClass().getName() + ": " + t.getMessage());
                    Throwable cause = t.getCause();
                    while (cause != null) {
                        System.out.println("  caused by " + cause.getClass().getName() + ": " + cause.getMessage());
                        cause = cause.getCause();
                    }
                    t.printStackTrace(System.out);
                }
            }
        } catch (Throwable t) {
            System.out.println("LOADER FAILED " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
        }
    }
}
