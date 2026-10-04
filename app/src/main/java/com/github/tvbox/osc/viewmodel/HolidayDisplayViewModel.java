package com.github.tvbox.osc.viewmodel;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.github.tvbox.osc.calendar.CalendarDayInfo;
import com.github.tvbox.osc.calendar.HolidayCatalog;
import com.github.tvbox.osc.util.holiday.HolidayCalendarClock;
import com.github.tvbox.osc.util.holiday.HolidayCatalogRepository;

/** Exposes the local day's holiday name after the bundled catalog finishes loading. */
public final class HolidayDisplayViewModel extends ViewModel {
    private final HolidayCatalogRepository catalogRepository;
    private final MutableLiveData<String> holidayName = new MutableLiveData<>("");
    private boolean cleared;

    private HolidayDisplayViewModel(HolidayCatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public LiveData<String> getHolidayName() {
        return holidayName;
    }

    /** Called when the page appears or the local date/time zone changes. */
    public void refresh() {
        if (catalogRepository.isLoadFinished()) {
            publishCurrentDay();
        } else {
            catalogRepository.loadAsync(() -> {
                if (!cleared) publishCurrentDay();
            });
        }
    }

    private void publishCurrentDay() {
        HolidayCatalog catalog = catalogRepository.getCached();
        String name = CalendarDayInfo.from(HolidayCalendarClock.now(), catalog).getHolidayText();
        holidayName.setValue(name == null ? "" : name);
    }

    @Override
    protected void onCleared() {
        cleared = true;
    }

    public static final class Factory implements ViewModelProvider.Factory {
        private final HolidayCatalogRepository catalogRepository;

        public Factory(Context context) {
            this.catalogRepository = new HolidayCatalogRepository(context);
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (!modelClass.isAssignableFrom(HolidayDisplayViewModel.class)) {
                throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
            }
            return (T) new HolidayDisplayViewModel(catalogRepository);
        }
    }
}
