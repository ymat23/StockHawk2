
package com.maty.android.stockhawk;

import android.app.LoaderManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;

import com.afollestad.materialdialogs.MaterialDialog;
import com.maty.android.stockhawk.data.QuoteColumns;
import com.maty.android.stockhawk.data.QuoteProvider;
import com.maty.android.stockhawk.rest.QuoteCursorAdapter;
import com.maty.android.stockhawk.rest.RecyclerViewItemClickListener;
import com.maty.android.stockhawk.widget.helper.ItemTouchHelperCallback;
import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.PeriodicTask;
import com.google.android.gms.gcm.Task;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;

/**
 * An activity representing a list of Stocks. This activity
 * has different presentations for handset and tablet-size devices. On
 * handsets, the activity presents a list of items, which when touched,
 * lead to a {@link StockDetailActivity} representing
 * item details. On tablets, the activity presents the list of items and
 * item details side-by-side using two vertical panes.
 */
public class StockListActivity extends AppCompatActivity implements
        LoaderManager.LoaderCallbacks<Cursor>, RecyclerViewItemClickListener.OnItemClickListener {

    public static final int CHANGE_UNITS_DOLLARS = 0;
    public static final int CHANGE_UNITS_PERCENTAGES = 1;
    private static final int CURSOR_LOADER_ID = 0;
    private final String EXTRA_CHANGE_UNITS = "EXTRA_CHANGE_UNITS";
    private final String EXTRA_ADD_DIALOG_OPENED = "EXTRA_ADD_DIALOG_OPENED";

    private int mChangeUnits = CHANGE_UNITS_DOLLARS;
    private QuoteCursorAdapter mAdapter;
    private boolean mTwoPane;
    private MaterialDialog mDialog;

    @Bind(R.id.stock_list)
    RecyclerView mRecyclerView;
    @Bind(R.id.empty_state_no_connection)
    View mEmptyStateNoConnection;
    @Bind(R.id.empty_state_no_stocks)
    View mEmptyStateNoStocks;
    @Bind(R.id.progress)
    ProgressBar mProgressBar;
    @Bind(R.id.coordinator_layout)
    CoordinatorLayout mCoordinatorLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stock_list);
        ButterKnife.bind(this);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }

        if (findViewById(R.id.stock_detail_container) != null) {
            mTwoPane = true;
        }

        if (savedInstanceState == null) {
            // The intent service is for executing immediate pulls from the Yahoo API
            // GCMTaskService can only schedule tasks, they cannot execute immediately
            Intent stackServiceIntent = new Intent(this, StockIntentService.class);
            // Run the initialize task service so that some stocks appear upon an empty database
            stackServiceIntent.putExtra(StockIntentService.EXTRA_TAG, StockIntentService.ACTION_INIT);
            if (isNetworkAvailable()) {
                startService(stackServiceIntent);
            } else {
                Snackbar.make(mCoordinatorLayout, getString(R.string.no_internet_connection),
                        Snackbar.LENGTH_LONG).show();
            }
        } else {
            mChangeUnits = savedInstanceState.getInt(EXTRA_CHANGE_UNITS);
            if (savedInstanceState.getBoolean(EXTRA_ADD_DIALOG_OPENED, false)) {
                showDialogForAddingStock();
            }
        }

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.addOnItemTouchListener(new RecyclerViewItemClickListener(this, this));

        mAdapter = new QuoteCursorAdapter(this, null, mChangeUnits);
        mRecyclerView.setAdapter(mAdapter);

        getLoaderManager().initLoader(CURSOR_LOADER_ID, null, this);

        ItemTouchHelper.Callback callback = new ItemTouchHelperCallback(mAdapter);
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(mRecyclerView);

        // Create a periodic task to pull stocks once every hour after the app has been opened.
        // This is so Widget data stays up to date.
        PeriodicTask periodicTask = new PeriodicTask.Builder()
                .setService(StockTaskService.class)
                .setPeriod(/* 1h */ 60 * 60)
                .setFlex(/* 10s */ 10)
                .setTag(StockTaskService.TAG_PERIODIC)
                .setRequiredNetwork(Task.NETWORK_STATE_CONNECTED)
                .setRequiresCharging(false)
                .build();
        // Schedule task with tag "periodic." This ensure that only the stocks present in the DB
        // are updated.
        GcmNetworkManager.getInstance(this).schedule(periodicTask);

    }

    @Override
    public void onResume() {
        super.onResume();
        getLoaderManager().restartLoader(CURSOR_LOADER_ID, null, this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.stocks_activity, menu);
        return true;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_CHANGE_UNITS, mChangeUnits);
        if (mDialog != null) {
            outState.putBoolean(EXTRA_ADD_DIALOG_OPENED, mDialog.isShowing());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_change_units) {
            if (mChangeUnits == CHANGE_UNITS_DOLLARS) {
                mChangeUnits = CHANGE_UNITS_PERCENTAGES;
            } else {
                mChangeUnits = CHANGE_UNITS_DOLLARS;
            }
            mAdapter.setChangeUnits(mChangeUnits);
            mAdapter.notifyDataSetChanged();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        mEmptyStateNoConnection.setVisibility(View.GONE);
        mEmptyStateNoStocks.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.VISIBLE);
        return new CursorLoader(this, QuoteProvider.Quotes.CONTENT_URI,
                new String[]{QuoteColumns._ID, QuoteColumns.SYMBOL, QuoteColumns.BIDPRICE,
                        QuoteColumns.PERCENT_CHANGE, QuoteColumns.CHANGE, QuoteColumns.ISUP},
                QuoteColumns.ISCURRENT + " = ?",
                new String[]{"1"},
                null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mProgressBar.setVisibility(View.GONE);
        mAdapter.swapCursor(data);

        if (mAdapter.getItemCount() == 0) {
            if (!isNetworkAvailable()) {
                mEmptyStateNoConnection.setVisibility(View.VISIBLE);
            } else {
                mEmptyStateNoStocks.setVisibility(View.VISIBLE);
            }
        }
        else
        {
            mEmptyStateNoConnection.setVisibility(View.GONE);
            mEmptyStateNoStocks.setVisibility(View.GONE);
        }

        if (!isNetworkAvailable()) {
            Snackbar.make(mCoordinatorLayout, getString(R.string.offline),
                    Snackbar.LENGTH_INDEFINITE).setAction(R.string.try_again, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    getLoaderManager().restartLoader(CURSOR_LOADER_ID, null, StockListActivity.this);
                }
            }).show();
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    @SuppressWarnings("unused")
    @OnClick(R.id.fab)
    public void showDialogForAddingStock() {
        if (isNetworkAvailable()) {
            mDialog = new MaterialDialog.Builder(this).title(R.string.add_symbol)
                    .inputType(InputType.TYPE_CLASS_TEXT)
                    .autoDismiss(true)
                    .positiveText(R.string.add)
                    .negativeText(R.string.disagree)
                    .input(R.string.input_hint, R.string.input_pre_fill, false,
                            new MaterialDialog.InputCallback() {
                                @Override
                                public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                                    addStockQuote(input.toString());
                                }
                            }).build();
            mDialog.show();

        } else {
            Snackbar.make(mCoordinatorLayout, getString(R.string.no_internet_connection),
                    Snackbar.LENGTH_LONG).setAction(R.string.try_again, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDialogForAddingStock();
                }
            }).show();
        }
    }

    @Override
    public void onItemClick(View v, int position) {
        if (mTwoPane) {
            Bundle arguments = new Bundle();
            arguments.putString(StockDetailFragment.ARG_SYMBOL, mAdapter.getSymbol(position));
            StockDetailFragment fragment = new StockDetailFragment();
            fragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.stock_detail_container, fragment)
                    .commit();
        } else {
            Context context = v.getContext();
            Intent intent = new Intent(context, StockDetailActivity.class);
            intent.putExtra(StockDetailFragment.ARG_SYMBOL, mAdapter.getSymbol(position));
            context.startActivity(intent);
        }
    }

    private void addStockQuote(final String stockQuote) {
        // On FAB click, receive user input. Make sure the stock doesn't already exist
        // in the DB and proceed accordingly.
        new AsyncTask<Void, Void, Boolean>() {

            @Override
            protected Boolean doInBackground(Void... params) {
                Cursor cursor = getContentResolver().query(QuoteProvider.Quotes.CONTENT_URI,
                        new String[]{QuoteColumns.SYMBOL},
                        QuoteColumns.SYMBOL + "= ?",
                        new String[]{stockQuote},
                        null);
                if (cursor != null) {
                    cursor.close();
                    return cursor.getCount() != 0;
                }
                return Boolean.FALSE;
            }

            @Override
            protected void onPostExecute(Boolean stockAlreadySaved) {
                if (stockAlreadySaved) {
                    Snackbar.make(mCoordinatorLayout, R.string.stock_already_saved,
                            Snackbar.LENGTH_LONG).show();
                } else {
                    Intent stockIntentService = new Intent(StockListActivity.this,
                            StockIntentService.class);
                    stockIntentService.putExtra(StockIntentService.EXTRA_TAG, StockIntentService.ACTION_ADD);
                    stockIntentService.putExtra(StockIntentService.EXTRA_SYMBOL, stockQuote);
                    startService(stockIntentService);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        return activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();
    }
}
