package org.wikipedia.search;

import android.content.*;
import android.os.*;
import android.support.v4.app.*;
import android.support.v4.widget.*;
import android.support.v7.widget.PopupMenu;
import android.text.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import com.squareup.otto.*;
import com.squareup.picasso.*;
import org.wikipedia.*;
import org.wikipedia.Utils;
import org.wikipedia.events.*;
import org.wikipedia.history.*;
import org.wikipedia.page.*;
import org.wikipedia.pageimages.*;

import java.util.*;

public class SearchArticlesFragment extends Fragment {
    private static final int DELAY_MILLIS = 300;

    private static final int MESSAGE_SEARCH = 1;

    private WikipediaApp app;

    private LinearLayout searchBarContainer;
    private LinearLayout navbar;
    private View searchBarIcon;
    private EditText searchTermText;
    private ListView searchResultsList;
    private ProgressBar searchProgress;
    private View searchNetworkError;
    private View searchNoResults;
    private ImageView searchBarMenuButton;
    private ImageView drawerIndicator;
    private ImageView wikipediaIcon;

    private int navbarColor;
    private int searchTermTextColor;
    private int searchTermHintTextColor;

    private SearchResultAdapter adapter;

    private boolean isSearchActive = false;

    private ParcelableLruCache<List<PageTitle>> searchResultsCache = new ParcelableLruCache<List<PageTitle>>(4, List.class);
    private ParcelableLruCache<String> pageImagesCache = new ParcelableLruCache<String>(48, String.class);
    private String lastSearchedText;

    private Handler searchHandler;

    private DrawerLayout drawerLayout;

    private SearchArticlesTask curSearchTask;

    private boolean pausedStateOfZero;

    private void hideSearchResults() {
        searchResultsList.setVisibility(View.GONE);
        isSearchActive = false;
        searchHandler.removeMessages(MESSAGE_SEARCH);
        if (curSearchTask != null) {
            curSearchTask.cancel();
            curSearchTask = null;
        }
        searchProgress.setVisibility(View.INVISIBLE);
        searchNoResults.setVisibility(View.GONE);
    }

    /**
     * Displays results passed to it as search suggestions.
     *
     * @param results List of results to display. If null, clears the list of suggestions & hides it.
     */
    private void displayResults(List<PageTitle> results) {
        adapter.setResults(results);
        ((BaseAdapter)searchResultsList.getAdapter()).notifyDataSetInvalidated();
        if (results.size() == 0) {
            searchNoResults.setVisibility(View.VISIBLE);
        } else {
            searchResultsList.setVisibility(View.VISIBLE);
            PageImagesTask imagesTask = new PageImagesTask(
                    app.getAPIForSite(app.getPrimarySite()),
                    app.getPrimarySite(),
                    results,
                    (int)(48f * WikipediaApp.SCREEN_DENSITY)) {
                @Override
                public void onFinish(Map<PageTitle, String> result) {
                    for (Map.Entry<PageTitle, String> entry : result.entrySet()) {
                        if (entry.getValue() == null) {
                            continue;
                        }
                        pageImagesCache.put(entry.getKey().getPrefixedText(), entry.getValue());
                    }
                    ((BaseAdapter)searchResultsList.getAdapter()).notifyDataSetInvalidated();
                }

                @Override
                public void onCatch(Throwable caught) {
                    // Don't actually do anything.
                    // Thumbnails are expendable
                }
            };
            imagesTask.execute();
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        app = (WikipediaApp)getActivity().getApplicationContext();
        LinearLayout parentLayout = (LinearLayout) inflater.inflate(R.layout.fragment_search, container, false);

        if (savedInstanceState != null) {
            searchResultsCache = savedInstanceState.getParcelable("searchResultsCache");
            pageImagesCache = savedInstanceState.getParcelable("pageImagesCache");
            lastSearchedText = savedInstanceState.getString("lastSearchedText");
            isSearchActive = savedInstanceState.getBoolean("isSearchActive");
            pausedStateOfZero = savedInstanceState.getBoolean("pausedStateOfZero");
        }

        searchBarContainer = (LinearLayout) parentLayout.findViewById(R.id.search_bar_container);
        navbar = (LinearLayout) parentLayout.findViewById(R.id.navbar);
        searchTermText = (EditText) parentLayout.findViewById(R.id.search_term_text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                navbarColor = ((ColorDrawable) (searchBarContainer.getBackground())).getColor();
            } else {
                navbarColor = Color.WHITE;
            }
            searchTermTextColor = searchTermText.getCurrentTextColor();
            searchTermHintTextColor = searchTermText.getCurrentHintTextColor();
        } catch (Exception e) {
            // just in case something in the layout changes out from underneath this code
            navbarColor = Color.WHITE;
            searchTermTextColor = Color.BLACK;
            searchTermHintTextColor = Color.GRAY;
        }
        searchResultsList = (ListView) parentLayout.findViewById(R.id.search_results_list);
        searchProgress = (ProgressBar) parentLayout.findViewById(R.id.search_progress);
        searchBarIcon = parentLayout.findViewById(R.id.search_bar_icon);
        searchNetworkError = parentLayout.findViewById(R.id.search_network_error);
        searchBarMenuButton = (ImageView)parentLayout.findViewById(R.id.search_bar_show_menu);
        drawerIndicator = (ImageView)parentLayout.findViewById(R.id.search_drawer_indicator);
        wikipediaIcon = (ImageView)parentLayout.findViewById(R.id.wikipedia_icon);
        searchNoResults = parentLayout.findViewById(R.id.search_results_empty);

        PopupMenu pageActionsMenu = new PopupMenu(getActivity(), searchBarMenuButton);
        PageActionsHandler pageActionsHandler = new PageActionsHandler(app.getBus(), pageActionsMenu, searchBarMenuButton);

        searchHandler = new Handler(new SearchHandlerCallback());

        searchResultsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PageTitle title = (PageTitle) searchResultsList.getAdapter().getItem(position);
                navigateToTitle(title);
            }
        });

        searchNetworkError.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Just retry!
                startSearch(searchTermText.getText().toString());
                searchNetworkError.setVisibility(View.GONE);
            }
        });

        searchTermText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // DO NOTHING!
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // DO NOTHING
            }

            @Override
            public void afterTextChanged(final Editable s) {
                searchNoResults.setVisibility(View.GONE);
                startSearch(s.toString());
            }
        });

        searchTermText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawerLayout.isDrawerOpen(Gravity.START)) {
                    drawerLayout.closeDrawer(Gravity.START);
                }
                startSearch(searchTermText.getText().toString());
            }
        });

        searchTermText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int action, KeyEvent keyEvent) {
                if (action == EditorInfo.IME_ACTION_GO) {
                    String searchTerm = searchTermText.getText().toString();
                    if (searchTerm.length() > 0) {
                        PageTitle title = new PageTitle(searchTermText.getText().toString(), app.getPrimarySite());
                        navigateToTitle(title);
                    } else {
                        hideSearchResults();
                    }
                    return true;
                }
                return false;
            }
        });

        adapter = new SearchResultAdapter(inflater);
        searchResultsList.setAdapter(adapter);

        searchBarIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawerLayout.isDrawerVisible(Gravity.START)) {
                    drawerLayout.closeDrawer(Gravity.START);
                } else {
                    hideSearchResults();
                    drawerLayout.openDrawer(Gravity.START);
                }
            }
        });

        return parentLayout;
    }

    private void navigateToTitle(PageTitle title) {
        HistoryEntry historyEntry = new HistoryEntry(title, HistoryEntry.SOURCE_SEARCH);
        InputMethodManager keyboard = (InputMethodManager)app.getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(searchTermText.getWindowToken(), 0);
        app.getBus().post(new NewWikiPageNavigationEvent(title, historyEntry));
        hideSearchResults();
        // remove focus from the Search field, so that the cursor stops blinking
        searchTermText.clearFocus();
    }

    private void startSearch(String term) {
        if (Utils.compareStrings(term, lastSearchedText) && isSearchActive) {
            return; // Nothing has changed!
        }
        if (term.equals("")) {
            hideSearchResults();
            return;
        }

        List<PageTitle> cacheResult = searchResultsCache.get(app.getPrimaryLanguage() + "-" + term);
        if (cacheResult != null) {
            displayResults(cacheResult);
            isSearchActive = true;
            return;
        }
        searchHandler.removeMessages(MESSAGE_SEARCH);
        Message searchMessage = Message.obtain();
        searchMessage.what = MESSAGE_SEARCH;
        searchMessage.obj = term;

        searchHandler.sendMessageDelayed(searchMessage, DELAY_MILLIS);
    }

    public void ensureVisible() {
        ViewAnimations.ensureTranslationY(getView(), 0);
    }

    public void setDrawerLayout(DrawerLayout drawerLayout) {
        this.drawerLayout = drawerLayout;

        drawerLayout.setDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            private boolean hideKeyboardCalled = false;
            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                // Hide the keyboard when the drawer is opened
                if (!hideKeyboardCalled) {
                    Utils.hideSoftKeyboard(getActivity());
                    hideKeyboardCalled = true;
                }
                // Make sure that the entire search bar is visible
                ensureVisible();
                // Animation for sliding drawer open and close
                // -4dp seems to match how much farther GMail's indicator goes, so sticking with it
                // Shift left and right margins appropriately to make sure that the rest of the layout does not shift
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(drawerIndicator.getLayoutParams());
                params.leftMargin = (int)(-4 * WikipediaApp.SCREEN_DENSITY * (slideOffset));
                params.rightMargin = -params.leftMargin;
                params.gravity = Gravity.CENTER_VERTICAL; // Needed because this seems to get reset otherwise. hmpf.
                drawerIndicator.setLayoutParams(params);
            }
            @Override
            public void onDrawerStateChanged(int newState) {
                if (newState == DrawerLayout.STATE_IDLE) {
                    hideKeyboardCalled = false;
                }
            }
        });

    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable("searchResultsCache", searchResultsCache);
        outState.putParcelable("pageImagesCache", pageImagesCache);
        outState.putString("lastSearchedText", lastSearchedText);
        outState.putBoolean("isSearchActive", isSearchActive);
        outState.putBoolean("pausedStateOfZero", pausedStateOfZero);
    }

    private final class SearchResultAdapter extends BaseAdapter {
        private List<PageTitle> results;
        private final LayoutInflater inflater;

        private SearchResultAdapter(LayoutInflater inflater) {
            this.inflater = inflater;
        }

        private List<PageTitle> getResults() {
            return results;
        }

        private void setResults(List<PageTitle> results) {
            this.results = results;
        }

        @Override
        public int getCount() {
            return results == null ? 0 : results.size();
        }

        @Override
        public Object getItem(int position) {
            return results.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_search_result, parent, false);
            }
            TextView pageTitleText = (TextView) convertView.findViewById(R.id.result_text);
            PageTitle title = (PageTitle) getItem(position);
            pageTitleText.setText(title.getDisplayText());
            ImageView imageView = (ImageView) convertView.findViewById(R.id.result_image);

            String thumbnail = pageImagesCache.get(title.getPrefixedText());
            if (thumbnail == null) {
                Picasso.with(getActivity())
                        .load(R.drawable.ic_pageimage_placeholder)
                        .into(imageView);
            } else {
                Picasso.with(getActivity())
                        .load(thumbnail)
                        .placeholder(R.drawable.ic_pageimage_placeholder)
                        .error(R.drawable.ic_pageimage_placeholder)
                        .into(imageView);
            }

            return convertView;
        }
    }

    /**
     * Handle back button being pressed.
     *
     * @return true if the back button press was handled, false otherwise
     */
    public boolean handleBackPressed() {
        if (isSearchActive) {
            hideSearchResults();
            return true;
        }
        return false;
    }

    @Subscribe
    public void onNewWikiPageNavigationEvent(NewWikiPageNavigationEvent event) {
        if (event.getHistoryEntry().getSource() != HistoryEntry.SOURCE_SEARCH) {
            // Clear navigation text if we used something other than search to go to the new page
            searchTermText.setText("");
        }
        // If search bar isn't fully visible, make it so!
        ensureVisible();
    }

    @Subscribe
    public void onWikipediaZeroStateChangeEvent(WikipediaZeroStateChangeEvent event) {
        if (WikipediaApp.getWikipediaZeroDisposition()) {
            setWikipediaZeroChrome();
        } else {
            setNormalChrome();
        }
    }

    private void setWikipediaZeroChrome() {
        navbar.setBackgroundColor(Color.BLACK);
        drawerIndicator.setColorFilter(Color.WHITE);
        wikipediaIcon.setColorFilter(Color.WHITE);
        searchTermText.setTextColor(Color.WHITE);
        searchTermText.setHint(R.string.zero_search_hint);
        searchBarMenuButton.setColorFilter(Color.WHITE);
        ensureVisible();
    }

    private void setNormalChrome() {
        navbar.setBackgroundColor(navbarColor);
        drawerIndicator.clearColorFilter();
        wikipediaIcon.clearColorFilter();
        searchTermText.setTextColor(searchTermTextColor);
        searchTermText.setHintTextColor(searchTermHintTextColor);
        searchTermText.setHint(R.string.search_hint);
        searchBarMenuButton.clearColorFilter();
        ensureVisible();
    }

    @Override
    public void onPause() {
        super.onPause();
        pausedStateOfZero = WikipediaApp.getWikipediaZeroDisposition();
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean latestWikipediaZeroDispostion = WikipediaApp.getWikipediaZeroDisposition();
        if (pausedStateOfZero != latestWikipediaZeroDispostion) {
            app.getBus().post(new WikipediaZeroStateChangeEvent());
        } else if (latestWikipediaZeroDispostion) {
            setWikipediaZeroChrome();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        app.getBus().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        app.getBus().unregister(this);
    }

    private class SearchHandlerCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message msg) {
            final String searchTerm = (String) msg.obj;
            SearchArticlesTask searchTask = new SearchArticlesTask(app, app.getAPIForSite(app.getPrimarySite()), app.getPrimarySite(), searchTerm) {
                @Override
                public void onFinish(List<PageTitle> result) {
                    searchProgress.setVisibility(View.INVISIBLE);
                    searchNetworkError.setVisibility(View.GONE);
                    displayResults(result);
                    searchResultsCache.put(app.getPrimaryLanguage() + "-" + searchTerm, result);
                    lastSearchedText = searchTerm;
                    curSearchTask = null;
                }

                @Override
                public void onCatch(Throwable caught) {
                    searchProgress.setVisibility(View.INVISIBLE);
                    searchNetworkError.setVisibility(View.VISIBLE);
                    searchResultsList.setVisibility(View.GONE);
                    curSearchTask = null;
                }

                @Override
                public void onBeforeExecute() {
                    searchProgress.setVisibility(View.VISIBLE);
                    isSearchActive = true;
                }
            };
            if (curSearchTask != null) {
                // This does not cancel the HTTP request itself
                // But it does cancel th execution of onFinish
                // This makes sure that a slower previous search query does not override
                // the results of a newer search query
                curSearchTask.cancel();
            }
            curSearchTask = searchTask;
            searchTask.execute();
            return true;
        }
    }
}
