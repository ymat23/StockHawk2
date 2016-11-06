
package com.maty.android.stockhawk.appwidget;

import android.content.Intent;
import android.widget.RemoteViewsService;

/**
 * </p>
 * The service to be connected to for a remote adapter to request RemoteViews for StockWidget.
 */
public class StockWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsService.RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new StockWidgetFactory(getApplicationContext(), intent);
    }
}
