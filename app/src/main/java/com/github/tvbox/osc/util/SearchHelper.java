package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SearchHelper {

    public static HashMap<String, String> getSourcesForSearch() {
        HashMap<String, String> mCheckSources;
        try {
            String api = SubscriptionConfig.getApiUrl();
            if(api.isEmpty())return null;
            HashMap<String, HashMap<String, String>> mCheckSourcesForApi = SubscriptionConfig.getCheckedSources();
            mCheckSources = mCheckSourcesForApi.get(api);
        } catch (Exception e) {
            return null;
        }
        if (mCheckSources == null || mCheckSources.isEmpty()) mCheckSources = getSources();
        return mCheckSources;
    }

    public static void putCheckedSources(HashMap<String, String> mCheckSources,boolean isAll) {
        String api = SubscriptionConfig.getApiUrl();
        if (api.isEmpty()) {
            return;
        }
        HashMap<String, HashMap<String, String>> mCheckSourcesForApi = SubscriptionConfig.getCheckedSources();

        if(isAll){
            if (mCheckSourcesForApi.containsKey(api)) mCheckSourcesForApi.remove(api);
        }else {
            mCheckSourcesForApi.put(api, mCheckSources);
        }
        FastSearchActivity.Companion.setCheckedSourcesForSearch(mCheckSources);
        SubscriptionConfig.setCheckedSources(mCheckSourcesForApi);
    }

    public static HashMap<String, String> getSources(){
        HashMap<String, String> mCheckSources = new HashMap<>();
        for (SourceBean bean : SourceConfigProviders.get().getSourceBeanList()) {
            if (!bean.isSearchable()) {
                continue;
            }
            mCheckSources.put(bean.getKey(), "1");
        }
        return mCheckSources;
    }

    /**
     * 词表来源:原文恒在首位;随后按 ASCII 词(\W+)拆分补候选词。
     * 注:\w 为 ASCII 语义,中文/标点都被当作分隔符,拆分可能产出空串(如"中文 xxx"),
     * 空串一律过滤,避免快速搜索词表出现空候选词。
     */
    public static List<String> splitWords(String text) {
        List<String> result = new ArrayList<>();
        result.add(text);
        String[] parts = text.split("\\W+");
        if (parts.length > 1) {
            for (String part : parts) {
                if (!part.isEmpty()) result.add(part);
            }
        }
        return result;
    }

}
