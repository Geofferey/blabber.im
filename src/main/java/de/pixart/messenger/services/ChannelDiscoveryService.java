package de.pixart.messenger.services;

import androidx.annotation.NonNull;
import android.util.Log;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.pixart.messenger.Config;
import de.pixart.messenger.http.HttpConnectionManager;
import de.pixart.messenger.http.services.MuclumbusService;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ChannelDiscoveryService {

    private final XmppConnectionService service;

    private MuclumbusService muclumbusService;

    private final Cache<String, List<MuclumbusService.Room>> cache;

    ChannelDiscoveryService(XmppConnectionService service) {
        this.service = service;
        this.cache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    }

    void initializeMuclumbusService() {
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        if (service.useTorToConnect()) {
            try {
                builder.proxy(HttpConnectionManager.getProxy());
            } catch (IOException e) {
                throw new RuntimeException("Unable to use Tor proxy", e);
            }
        }
        try {
            builder.networkInterceptors().add(new UserAgentInterceptor(service.getIqGenerator().getUserAgent()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        Retrofit retrofit = new Retrofit.Builder()
                .client(builder.build())
                .baseUrl(Config.CHANNEL_DISCOVERY)
                .addConverterFactory(GsonConverterFactory.create())
                .callbackExecutor(Executors.newSingleThreadExecutor())
                .build();
        this.muclumbusService = retrofit.create(MuclumbusService.class);
    }

    void discover(String query, OnChannelSearchResultsFound onChannelSearchResultsFound) {
        final boolean all = query == null || query.trim().isEmpty();
        List<MuclumbusService.Room> result = cache.getIfPresent(all ? "" : query);
        if (result != null) {
            onChannelSearchResultsFound.onChannelSearchResultsFound(result);
            return;
        }
        if (all) {
            discoverChannels(onChannelSearchResultsFound);
        } else {
            discoverChannels(query, onChannelSearchResultsFound);
        }
    }

    private void discoverChannels(OnChannelSearchResultsFound listener) {
        Call<MuclumbusService.Rooms> call = muclumbusService.getRooms(1);
        try {
            call.enqueue(new Callback<MuclumbusService.Rooms>() {
                @Override
                public void onResponse(@NonNull Call<MuclumbusService.Rooms> call, @NonNull Response<MuclumbusService.Rooms> response) {
                    final MuclumbusService.Rooms body = response.body();
                    if (body == null) {
                        listener.onChannelSearchResultsFound(Collections.emptyList());
                        logError(response);
                        return;
                    }
                    cache.put("", body.items);
                    listener.onChannelSearchResultsFound(body.items);
                }

                @Override
                public void onFailure(@NonNull Call<MuclumbusService.Rooms> call, @NonNull Throwable throwable) {
                    Log.d(Config.LOGTAG, "Unable to query muclumbus on " + Config.CHANNEL_DISCOVERY, throwable);
                    listener.onChannelSearchResultsFound(Collections.emptyList());
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void discoverChannels(final String query, OnChannelSearchResultsFound listener) {
        MuclumbusService.SearchRequest searchRequest = new MuclumbusService.SearchRequest(query);
        Call<MuclumbusService.SearchResult> searchResultCall = muclumbusService.search(searchRequest);
        searchResultCall.enqueue(new Callback<MuclumbusService.SearchResult>() {
            @Override
            public void onResponse(@NonNull Call<MuclumbusService.SearchResult> call, @NonNull Response<MuclumbusService.SearchResult> response) {
                final MuclumbusService.SearchResult body = response.body();
                if (body == null) {
                    listener.onChannelSearchResultsFound(Collections.emptyList());
                    logError(response);
                    return;
                }
                cache.put(query, body.result.items);
                listener.onChannelSearchResultsFound(body.result.items);
            }

            @Override
            public void onFailure(@NonNull Call<MuclumbusService.SearchResult> call, @NonNull Throwable throwable) {
                Log.d(Config.LOGTAG, "Unable to query muclumbus on " + Config.CHANNEL_DISCOVERY, throwable);
                listener.onChannelSearchResultsFound(Collections.emptyList());
            }
        });
    }

    private static void logError(final Response response) {
        final ResponseBody errorBody = response.errorBody();
        Log.d(Config.LOGTAG, "code from muclumbus=" + response.code());
        if (errorBody == null) {
            return;
        }
        try {
            Log.d(Config.LOGTAG, "error body=" + errorBody.string());
        } catch (IOException e) {
            //ignored
        }
    }

    public interface OnChannelSearchResultsFound {
        void onChannelSearchResultsFound(List<MuclumbusService.Room> results);
    }

    private class UserAgentInterceptor implements Interceptor {
        private final String userAgent;

        UserAgentInterceptor(String userAgent) {
            this.userAgent = userAgent;
        }

        @NotNull
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();
            Request requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", userAgent)
                    .build();
            return chain.proceed(requestWithUserAgent);
        }
    }
}