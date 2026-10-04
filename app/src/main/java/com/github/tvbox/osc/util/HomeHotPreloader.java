package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.github.tvbox.osc.bean.Movie;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fetches the first Douban home list while the splash is visible, without creating any views. */
public final class HomeHotPreloader {
    private static final Gson GSON = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<List<Movie.Video>> videos = new MutableLiveData<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private boolean settled;

    public LiveData<List<Movie.Video>> videos() {
        return videos;
    }

    /** Read on the main thread, where LiveData is delivered. An empty list is a completed failure. */
    public boolean isSettled() {
        return settled;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            Calendar calendar = Calendar.getInstance();
            int year = calendar.get(Calendar.YEAR);
            String today = HomeHotCache.dayKey(year, calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DATE));
            try {
                if (today.equals(HomeHotCache.getDay())) {
                    ArrayList<Movie.Video> cached = parse(HomeHotCache.getData());
                    if (!cached.isEmpty()) {
                        mainHandler.post(() -> settle(cached));
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // A broken cache falls back to the network.
            }
            mainHandler.post(() -> request(today, year));
        });
    }

    private void request(String today, int year) {
        String url = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10"
                + "&tags=&playable=1&start=0&year_range=" + year + "," + year;
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA.randomOne());
        try {
            HttpClient.get(url, headers, null, new HCallBack() {
                @Override
                public void onSuccess(String json) {
                    HeavyTaskUtil.getSerialExecutorService().execute(() -> {
                        ArrayList<Movie.Video> parsed = parse(json);
                        if (!parsed.isEmpty()) {
                            try {
                                HomeHotCache.save(today, json);
                            } catch (Throwable ignored) {
                                // A cache write failure does not discard fetched videos.
                            }
                        }
                        mainHandler.post(() -> settle(parsed));
                    });
                }

                @Override
                public void onError(Throwable error) {
                    mainHandler.post(() -> settle(new ArrayList<>()));
                }
            });
        } catch (Throwable error) {
            settle(new ArrayList<>());
        }
    }

    private void settle(List<Movie.Video> result) {
        if (settled) return;
        settled = true;
        videos.setValue(result);
    }

    /** The regular home refresh uses the same parser as the splash prefetch. */
    public static ArrayList<Movie.Video> parse(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject document = GSON.fromJson(json, JsonObject.class);
            JsonArray array = document.getAsJsonArray("data");
            for (JsonElement element : array) {
                JsonObject item = element.getAsJsonObject();
                Movie.Video video = new Movie.Video();
                video.name = item.get("title").getAsString();
                video.note = item.get("rate").getAsString();
                if (!video.note.isEmpty()) video.note += " 分";
                video.pic = item.get("cover").getAsString()
                        + "@Referer=https://movie.douban.com/@User-Agent=" + UA.random();
                result.add(video);
            }
        } catch (Throwable ignored) {
            // An invalid response is treated as an empty result, matching the page loader.
        }
        return result;
    }
}
