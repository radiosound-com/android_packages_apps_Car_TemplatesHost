package com.android.car.libraries.templates.conformance;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.LongMessageTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.ParkedOnlyOnClickListener;
import androidx.car.app.model.PlaceListMapTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.RowSection;
import androidx.car.app.model.SectionedItemTemplate;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Tab;
import androidx.car.app.model.TabContents;
import androidx.car.app.model.TabTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.model.signin.ProviderSignInMethod;
import androidx.car.app.model.signin.SignInTemplate;
import androidx.car.app.media.model.MediaPlaybackTemplate;
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate;

/**
 * Small deterministic app used to exercise every AndroidX Car App 1.7 model
 * against the stock and Caramel Vanilla renderer services.
 */
public final class ConformanceCarAppService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            private ConformanceScreen screen;

            @Override
            public Screen onCreateScreen(Intent intent) {
                screen = new ConformanceScreen(getCarContext(),
                        intent == null ? "grid" : intent.getStringExtra("mode"));
                return screen;
            }

            @Override
            public void onNewIntent(Intent intent) {
                super.onNewIntent(intent);
                if (screen != null && intent != null) {
                    screen.setMode(intent.getStringExtra("mode"));
                }
            }
        };
    }

    private static final class ConformanceScreen extends Screen {
        private String mode;
        private String activeTab = "one";

        ConformanceScreen(androidx.car.app.CarContext context, String mode) {
            super(context);
            this.mode = mode == null ? "grid" : mode;
        }

        void setMode(String mode) {
            this.mode = mode == null ? "grid" : mode;
            invalidate();
        }

        @Override
        public Template onGetTemplate() {
            switch (mode) {
                case "long-message": return longMessage();
                case "sign-in": return signIn();
                case "tabs": return tabs();
                case "sections": return sections();
                case "place-map": return placeMap();
                case "route-preview": return routePreview();
                case "media": return media();
                case "list": return list();
                case "pane": return pane();
                case "message": return message();
                case "search": return search();
                default: return grid();
            }
        }

        private Action action(String title) {
            return new Action.Builder().setTitle(title)
                    .setOnClickListener(ParkedOnlyOnClickListener.create(() -> { })).build();
        }

        private Row row(String title, String text) {
            return new Row.Builder().setTitle(title).addText(text)
                    .setOnClickListener(() -> { }).build();
        }

        private Row staticRow(String title, String text) {
            return new Row.Builder().setTitle(title).addText(text).build();
        }

        private ItemList rows() {
            return new ItemList.Builder()
                    .addItem(row("First row", "Secondary text"))
                    .addItem(row("Second row", "More content"))
                    .addItem(row("Third row", "A longer row for wrapping"))
                    .build();
        }

        private GridTemplate grid() {
            ItemList.Builder items = new ItemList.Builder();
            for (int i = 1; i <= 8; i++) {
                items.addItem(new GridItem.Builder().setTitle("Tile " + i)
                        .setText("Grid item").setImage(CarIcon.APP_ICON).setLoading(false)
                        .setOnClickListener(() -> { }).build());
            }
            return new GridTemplate.Builder().setTitle("Grid template")
                    .setSingleList(items.build()).setItemSize(GridTemplate.ITEM_SIZE_MEDIUM).build();
        }

        private ListTemplate list() {
            return new ListTemplate.Builder().setTitle("List template")
                    .setSingleList(rows()).build();
        }

        private PaneTemplate pane() {
            return new PaneTemplate.Builder(new Pane.Builder()
                    .addRow(staticRow("Pane row", "Pane description"))
                    .addRow(staticRow("Another row", "Another description"))
                    .addAction(action("Pane action")).build())
                    .setTitle("Pane template").build();
        }

        private MessageTemplate message() {
            return new MessageTemplate.Builder("A message template for renderer testing.")
                    .setTitle("Message template").addAction(action("Dismiss")).build();
        }

        private SearchTemplate search() {
            return new SearchTemplate.Builder(new SearchTemplate.SearchCallback() {
                @Override public void onSearchTextChanged(String text) { invalidate(); }
                @Override public void onSearchSubmitted(String text) { invalidate(); }
            }).setSearchHint("Search places").setItemList(rows()).build();
        }

        private LongMessageTemplate longMessage() {
            return new LongMessageTemplate.Builder(
                    "This is a long message with enough text to exercise wrapping and action placement in the host renderer.")
                    .setTitle("Long message").addAction(action("Continue")).build();
        }

        private SignInTemplate signIn() {
            return new SignInTemplate.Builder(new ProviderSignInMethod(action("Sign in")))
                    .setTitle("Sign in").setInstructions("Sign in to continue.")
                    .setAdditionalText("The host must keep the action reachable while parked.")
                    .build();
        }

        private TabTemplate tabs() {
            TabContents contents = new TabContents.Builder(
                    new MessageTemplate.Builder("Active tab content").setTitle("Tab content")
                            .addAction(action("Tab action")).build()).build();
            return new TabTemplate.Builder(new TabTemplate.TabCallback() {
                @Override public void onTabSelected(String contentId) {
                    activeTab = contentId;
                    invalidate();
                }
            }).addTab(new Tab.Builder().setTitle("One").setContentId("one")
                    .setIcon(CarIcon.APP_ICON).build())
                    .addTab(new Tab.Builder().setTitle("Two").setContentId("two")
                            .setIcon(CarIcon.APP_ICON).build())
                    .setHeaderAction(Action.APP_ICON).setActiveTabContentId(activeTab)
                    .setTabContents(contents).build();
        }

        private SectionedItemTemplate sections() {
            RowSection section = new RowSection.Builder().setTitle("Section one")
                    .addItem(row("Section row", "Section detail")).build();
            return new SectionedItemTemplate.Builder().addSection(section)
                    .setHeader(new Header.Builder().setTitle("Sections").build()).build();
        }

        private PlaceListMapTemplate placeMap() {
            return new PlaceListMapTemplate.Builder().setTitle("Place list map")
                    .setCurrentLocationEnabled(true).setLoading(true).build();
        }

        private RoutePreviewNavigationTemplate routePreview() {
            return new RoutePreviewNavigationTemplate.Builder().setTitle("Route preview")
                    .setLoading(true).build();
        }

        private MediaPlaybackTemplate media() {
            return new MediaPlaybackTemplate.Builder()
                    .setHeader(new Header.Builder().setTitle("Media playback").build()).build();
        }
    }
}
