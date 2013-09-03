package uk.ac.rdg.resc.ncwms.gwt.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gwtopenmaps.openlayers.client.OpenLayers;

import uk.ac.rdg.resc.ncwms.gwt.client.handlers.ElevationSelectionHandler;
import uk.ac.rdg.resc.ncwms.gwt.client.handlers.GodivaActionsHandler;
import uk.ac.rdg.resc.ncwms.gwt.client.handlers.LayerSelectionHandler;
import uk.ac.rdg.resc.ncwms.gwt.client.handlers.PaletteSelectionHandler;
import uk.ac.rdg.resc.ncwms.gwt.client.handlers.TimeDateSelectionHandler;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.ConnectionException;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.ErrorHandler;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.LayerDetails;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.LayerMenuItem;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.LayerRequestBuilder;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.LayerRequestCallback;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.LayerTreeJSONParser;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.TimeRequestBuilder;
import uk.ac.rdg.resc.ncwms.gwt.client.requests.TimeRequestCallback;
import uk.ac.rdg.resc.ncwms.gwt.client.widgets.GodivaWidgets;
import uk.ac.rdg.resc.ncwms.gwt.client.widgets.MapArea;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.http.client.URL;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootLayoutPanel;
import com.google.gwt.user.client.ui.VerticalPanel;

/**
 * Still TODO:
 * 
 * Make wms URL configurable
 * 
 * @author Guy Griffiths
 * 
 */
public abstract class BaseWmsClient implements EntryPoint, ErrorHandler, GodivaActionsHandler,
        LayerSelectionHandler, ElevationSelectionHandler, TimeDateSelectionHandler,
        PaletteSelectionHandler {

    /*
     * State variables.
     */
    protected int mapHeight;
    protected int mapWidth;
    protected String proxyUrl;
    protected String wmsUrl;
    protected String docHref;

    /*
     * We need this because the call to layerDetails (where we receive this
     * time) is separate to the call where we discover what actual times (as
     * opposed to dates) are available
     */
    private String nearestTime;

    /*
     * Map widget
     */
    protected MapArea mapArea;

    /*
     * A count of how many items we are currently waiting to load.
     */
    private int loadingCount;

    /*
     * These 3 booleans are used so that we only update the map when all
     * required data have been loaded
     */
    private boolean layerDetailsLoaded;
    private boolean dateTimeDetailsLoaded;
    private boolean minMaxDetailsLoaded;

    /**
     * This is the entry point for GWT.
     * 
     * Queries a config servlet and sets some global fields. If the config is
     * not present, or there is an error, sets some defaults, and calls the
     * initialisation method.
     */
    @Override
    public void onModuleLoad() {
        RequestBuilder getConfig = new RequestBuilder(RequestBuilder.GET, "getconfig");
        getConfig.setCallback(new RequestCallback() {
            @Override
            public void onResponseReceived(Request request, Response response) {
                try {
                    JSONValue jsonMap = JSONParser.parseLenient(response.getText());
                    JSONObject parentObj = jsonMap.isObject();
                    proxyUrl = parentObj.get("proxy").isString().stringValue();
                    docHref = parentObj.get("docLocation").isString().stringValue();
                    mapHeight = Integer.parseInt(parentObj.get("mapHeight").isString()
                            .stringValue());
                    mapWidth = Integer.parseInt(parentObj.get("mapWidth").isString().stringValue());
                    initBaseWms();
                } catch (Exception e) {
                    /*
                     * Catching a plain Exception - not something that should
                     * generally be done.
                     * 
                     * However...
                     * 
                     * We explicitly *do* want to handle *all* possible runtime
                     * exceptions in the same manner.
                     */
                    initWithDefaults();
                }
            }

            @Override
            public void onError(Request request, Throwable exception) {
                initWithDefaults();
            }
        });
        try {
            getConfig.send();
        } catch (RequestException e) {
            initWithDefaults();
        }
    }

    /**
     * Initializes the WMS client with some default settings.
     * 
     * Subclasses can override this to define new defaults
     */
    protected final void initWithDefaults() {
        mapHeight = 400;
        mapWidth = 512;
        /*
         * No proxy by default, because by default, we run on the same server as
         * ncWMS
         */
        proxyUrl = "";
        docHref = "http://www.resc.rdg.ac.uk/trac/ncWMS/wiki/GodivaTwoUserGuide";
        initBaseWms();
    }

    /**
     * Initialises the necessary elements and then passes to a subclass method
     * for layout and initialisation of other widgets
     */
    private void initBaseWms() {
        wmsUrl = getBaseWmsUrl();
        loadingCount = 0;
        mapArea = new MapArea(wmsUrl, mapWidth, mapHeight, this);

        /*
         * Call the subclass initialisation
         */
        init();
        /*
         * Set this at the last possible moment, so that subclasses can set it
         * if they like
         */
        OpenLayers.setProxyHost(proxyUrl);

        /*
         * Now request the menu from the ncWMS server
         */
        requestMenu();
    }

    /**
     * Gets the location of the WMS servlet
     * 
     * This can be overridden by subclasses so that e.g. the WMS can be set from
     * the URL etc. Note that this gets used at the very beginning, and so each
     * client only has ONE FINAL wms URL. ncWMS supports external WMSs anyway,
     * so this shouldn't be an issue
     */
    protected String getBaseWmsUrl() {
        return "wms";
    }

    /**
     * Builds a URL given the request name and a {@link Map} of parameters
     * 
     * @param request
     *            the request name (e.g. GetMap)
     * @param parameters
     *            a {@link Map} of parameters and their values
     * @return the URL of the request
     */
    private String getWmsUrl(String request, Map<String, String> parameters) {
        StringBuilder url = new StringBuilder();
        url.append("?request=" + request);
        for (String key : parameters.keySet()) {
            url.append("&" + key + "=" + parameters.get(key));
        }
        return getUrlFromGetArgs(url.toString());
    }

    /**
     * Encodes the URL, including proxy and base WMS URL
     * 
     * @param url
     *            the part of the URL representing the GET arguments
     * @return the encoded URL
     */
    private String getUrlFromGetArgs(String url) {
        return URL.encode(proxyUrl + wmsUrl + url);
    }

    /**
     * Gets the height of the map in the map widget
     * 
     * @return the height in pixels
     */
    protected int getMapHeight() {
        return mapHeight;
    }

    /**
     * Gets the width of the map in the map widget
     * 
     * @return the width in pixels
     */
    protected int getMapWidth() {
        return mapWidth;
    }

    /**
     * Requests the layer menu from the server. When the menu is returned,
     * menuLoaded will be called
     */
    protected void requestMenu() {
        RequestBuilder getMenuRequest = new RequestBuilder(RequestBuilder.GET,
                getUrlFromGetArgs("?request=GetMetadata&item=menu"));
        getMenuRequest.setCallback(new RequestCallback() {
            @Override
            public void onResponseReceived(Request req, Response response) {
                try {
                    if (response.getStatusCode() != Response.SC_OK) {
                        throw new ConnectionException("Error contacting server");
                    }
                    JSONValue jsonMap = JSONParser.parseLenient(response.getText());
                    JSONObject parentObj = jsonMap.isObject();
                    LayerMenuItem menuTree = LayerTreeJSONParser.getTreeFromJson(parentObj);

                    menuLoaded(menuTree);
                } catch (Exception e) {
                    invalidJson(e);
                } finally {
                    setLoading(false);
                }
            }

            @Override
            public void onError(Request request, Throwable e) {
                setLoading(false);
                handleError(e);
            }
        });

        try {
            setLoading(true);
            getMenuRequest.send();
        } catch (RequestException e) {
            handleError(e);
        }
    }

    /**
     * Request details about a particular layer. Once loaded, layerDetailsLoaded
     * will be called
     * 
     * @param layerId
     *            the ID of the layer whose details are desired
     * @param currentTime
     *            the time we want to know the closest time to. Can be null
     * @param autoZoomAndPalette
     *            true if we want to zoom to extents and possibly auto-adjust
     *            palette. Note that this will only auto-adjust the palette if
     *            the conditions are right
     */
    protected void requestLayerDetails(final String layerId, String currentTime,
            final boolean autoZoomAndPalette) {
        if (layerId == null) {
            /*
             * We have no variables defined in the selected layer
             * 
             * Return here. We are already dealing with the case where there are
             * no layers present.
             */
            return;
        }
        /*
         * The map should only get updated once all details are loaded
         */
        layerDetailsLoaded = false;
        dateTimeDetailsLoaded = false;
        minMaxDetailsLoaded = false;

        LayerRequestBuilder getLayerDetailsRequest = new LayerRequestBuilder(layerId, proxyUrl
                + wmsUrl, currentTime);

        getLayerDetailsRequest.setCallback(new LayerRequestCallback(layerId, this) {
            @Override
            public void onResponseReceived(Request req, Response response) {
                try {
                    super.onResponseReceived(req, response);
                    if (response.getStatusCode() != Response.SC_OK) {
                        throw new ConnectionException("Error contacting server");
                    }
                    /*
                     * Call a subclass method to deal with the layer details.
                     * This will normally make a call to populateWidgets, and
                     * may create extra widgets if needed (e.g. for multi-layer
                     * clients)
                     */
                    layerDetailsLoaded(getLayerDetails(), autoZoomAndPalette);

                    /*
                     * Select the nearest date. This will either be the nearest
                     * date to the current date/time, or the nearest date to the
                     * selected date/time, depending on whether currentTime is
                     * null or not
                     */
                    dateSelected(getLayerDetails().getId(), getLayerDetails().getNearestDate());

                    /*
                     * Zoom to extents and possible auto-adjust palette
                     */
                    if (autoZoomAndPalette) {
                        try {
                            mapArea.zoomToExtents(getLayerDetails().getExtents());
                        } catch (Exception e) {
                            handleError(e);
                        }
                        /*
                         * We request an auto-range. Since force=false, this
                         * will only request the auto-range if the server-side
                         * value has not been configured
                         */
                        maybeRequestAutoRange(getLayerDetails().getId(),
                                getWidgetCollection(getLayerDetails().getId())
                                        .getElevationSelector().getSelectedElevation(),
                                getWidgetCollection(getLayerDetails().getId()).getTimeSelector()
                                        .getSelectedDateTime(), false);
                    } else {
                        minMaxDetailsLoaded = true;
                    }
                    layerDetailsLoaded = true;
                    updateMapBase(layerId);
                } catch (Exception e) {
                    invalidJson(e);
                } finally {
                    setLoading(false);
                }
            }

            @Override
            public void onError(Request request, Throwable e) {
                setLoading(false);
                layerDetailsLoaded = true;
                updateMapBase(layerId);
                handleError(e);
            }
        });

        try {
            setLoading(true);
            getLayerDetailsRequest.send();
        } catch (RequestException e) {
            handleError(e);
        }
    }

    /**
     * Possibly requests the auto-detected scale range. This will make the
     * request if {@code force} is true, or we have a default scale range set on
     * the server
     * 
     * @param layerId
     *            the ID of the layer to request the scale range for
     * @param elevation
     *            the elevation
     * @param time
     *            the time
     * @param force
     *            whether to perform even if a scale has been set on the server
     */
    protected void maybeRequestAutoRange(final String layerId, String elevation, String time,
            boolean force) {
        minMaxDetailsLoaded = false;
        /*
         * If we have default values for the scale range or force=true, then
         * continue with the request, otherwise return
         */
        String[] scaleRangeSplit = getWidgetCollection(layerId).getPaletteSelector()
                .getScaleRange().split(",");
        if (!force
                && (Double.parseDouble(scaleRangeSplit[0]) != -50 || Double
                        .parseDouble(scaleRangeSplit[1]) != 50)) {
            minMaxDetailsLoaded = true;
            return;
        }

        Map<String, String> parameters = new HashMap<String, String>();
        /*
         * We use 1.1.1 here, because if getMap().getProjection() returns
         * EPSG:4326, getMap().getExtent().toBBox(4) will still return in
         * lon-lat order
         */
        parameters.put("item", "minmax");
        parameters.put("layers", layerId);
        parameters.put("srs", mapArea.getMap().getProjection());
        parameters.put("time", getWidgetCollection(layerId).getTimeSelector().getSelectedDateTime());
        parameters.put("height", "100");
        parameters.put("width", "100");
        parameters.put("version", "1.1.1");
        parameters.put("bbox", mapArea.getMap().getExtent().toBBox(4));
        if (elevation != null) {
            parameters.put("elevation", elevation);
        }
        if (nearestTime != null) {
            parameters.put("time", nearestTime);
        }

        RequestBuilder getMinMaxRequest = new RequestBuilder(RequestBuilder.GET, getWmsUrl(
                "GetMetadata", parameters));
        getMinMaxRequest.setCallback(new RequestCallback() {
            @Override
            public void onResponseReceived(Request req, Response response) {
                if (response.getText() != null && !response.getText().isEmpty()) {
                    try {
                        JSONValue jsonMap = JSONParser.parseLenient(response.getText());
                        JSONObject parentObj = jsonMap.isObject();
                        double min = parentObj.get("min").isNumber().doubleValue();
                        double max = parentObj.get("max").isNumber().doubleValue();
                        rangeLoaded(layerId, min, max);
                    } catch (Exception e) {
                        invalidJson(e);
                    }
                }
                minMaxDetailsLoaded = true;
                updateMapBase(layerId);
                setLoading(false);
            }

            @Override
            public void onError(Request request, Throwable exception) {
                // We have failed, but we still want to update the map
                setLoading(false);
                minMaxDetailsLoaded = true;
                updateMapBase(layerId);
                handleError(exception);
            }
        });
        try {
            setLoading(true);
            getMinMaxRequest.send();
        } catch (RequestException e) {
            handleError(e);
        }
    }

    @Override
    public void setLoading(boolean loading) {
        if (loading) {
            loadingCount++;
            if (loadingCount == 1) {
                loadingStarted();
            }
        } else {
            loadingCount--;
            if (loadingCount == 0) {
                loadingFinished();
            }
        }
    }

    /**
     * Handles the case where invalid data is sent back from the server
     * 
     * @param e
     */
    protected void invalidJson(Exception e) {
        e.printStackTrace();
        final DialogBox popup = new DialogBox();
        VerticalPanel v = new VerticalPanel();
        if (e instanceof ConnectionException) {
            v.add(new Label(e.getMessage()));
        } else {
            v.add(new Label("Invalid JSON returned from server: " + e.getMessage()));
        }
        popup.setText("Error");
        Button b = new Button();
        b.setText("Close");
        b.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                popup.hide();
            }
        });
        v.add(b);
        v.setCellHorizontalAlignment(b, HasHorizontalAlignment.ALIGN_CENTER);
        popup.setWidget(v);
        popup.center();
    }

    /**
     * Handles general exceptions.
     */
    @Override
    public void handleError(Throwable e) {
        /*
         * TODO Handle these better?
         */
        e.printStackTrace();
    }

    /**
     * Handles an error which is catastrophic.
     * 
     * @param message
     *            the message to display to the user
     */
    protected void handleCatastrophicError(String message) {
        /*
         * TODO combine error handling a little better
         */
        HTML errorMessage = new HTML();
        Window.setTitle("Unrecoverable Error");
        errorMessage.setHTML(message);
        RootLayoutPanel mainWindow = RootLayoutPanel.get();
        for (int i = 0; i < mainWindow.getWidgetCount(); i++) {
            mainWindow.remove(0);
        }
        mainWindow.add(errorMessage);
    }

    /**
     * Populates a set of widgets. {@link GodivaWidgets} contains all widgets
     * necessary for setting all server options (TODO what about style?).
     * 
     * @param layerDetails
     *            a {@link LayerDetails} object containing the layer details.
     *            This gets returned when layer details are loaded
     * @param widgetCollection
     *            a collection of widgets to populated.
     */
    protected void populateWidgets(LayerDetails layerDetails, GodivaWidgets widgetCollection) {
        // TODO Deal with null cases
        widgetCollection.getElevationSelector().setId(layerDetails.getId());
        widgetCollection.getTimeSelector().setId(layerDetails.getId());
        widgetCollection.getPaletteSelector().setId(layerDetails.getId());

        widgetCollection.getUnitsInfo().setUnits(layerDetails.getUnits());
        widgetCollection.getCopyrightInfo().setCopyrightInfo(layerDetails.getCopyright());
        widgetCollection.getMoreInfo().setInfo(layerDetails.getMoreInfo());

        widgetCollection.getElevationSelector().setUnitsAndDirection(layerDetails.getZUnits(),
                layerDetails.isZPositive());
        widgetCollection.getElevationSelector().populateVariables(layerDetails.getAvailableZs());

        widgetCollection.getPaletteSelector().populatePalettes(layerDetails.getAvailablePalettes());
        widgetCollection.getPaletteSelector().populateStyles(layerDetails.getSupportedStyles());

        widgetCollection.getTimeSelector().setContinuous(layerDetails.isContinuousTimeAxis());
        if(layerDetails.isContinuousTimeAxis()){
            List<String> startEndDates = new ArrayList<String>();
            startEndDates.add(layerDetails.getStartTime());
            startEndDates.add(layerDetails.getEndTime());
            widgetCollection.getTimeSelector().populateDates(startEndDates);
        } else {
            widgetCollection.getTimeSelector().populateDates(layerDetails.getAvailableDates());
            if (layerDetails.getNearestTime() != null) {
                nearestTime = layerDetails.getNearestTime();
                widgetCollection.getTimeSelector().selectDate(layerDetails.getNearestDate());
            }
        }

        if (!widgetCollection.getPaletteSelector().isLocked()) {
            widgetCollection.getPaletteSelector().setScaleRange(layerDetails.getScaleRange());
            widgetCollection.getPaletteSelector().setNumColorBands(layerDetails.getNumColorBands());
            widgetCollection.getPaletteSelector().setLogScale(layerDetails.isLogScale());
        }
    }

    /**
     * Performs tasks common to each map update, then calls the subclass method
     */
    private void updateMapBase(String layerUpdated) {
        if (layerDetailsLoaded && dateTimeDetailsLoaded && minMaxDetailsLoaded) {
            updateMap(mapArea, layerUpdated);
        }
    }

    @Override
    public void layerSelected(String layerId, boolean autoZoomAndPalette) {
        requestLayerDetails(layerId, getCurrentTime(), autoZoomAndPalette);
        updateMapBase(layerId);
    }

    public abstract String getCurrentTime();

    @Override
    public void layerDeselected(String layerId) {
        mapArea.removeLayer(layerId);
    }

    @Override
    public void refreshLayerList() {
        requestMenu();
    }

    @Override
    public void elevationSelected(String layerId, String elevation) {
        updateMapBase(layerId);
    }

    @Override
    public void paletteChanged(String layerId, String paletteName, String style, int nColorBands) {
        updateMapBase(layerId);
    }

    @Override
    public void scaleRangeChanged(String layerId, String scaleRange) {
        updateMapBase(layerId);
    }

    @Override
    public void logScaleChanged(String layerId, boolean newIsLogScale) {
        updateMapBase(layerId);
    }

    @Override
    public void autoAdjustPalette(String layerId) {
        maybeRequestAutoRange(layerId, getWidgetCollection(layerId).getElevationSelector()
                .getSelectedElevation(), getWidgetCollection(layerId).getTimeSelector()
                .getSelectedDateTime(), true);
    }

    @Override
    public void dateSelected(final String layerId, String selectedDate) {
        if (selectedDate == null) {
            dateTimeDetailsLoaded = true;
            updateMapBase(layerId);
            return;
        }
        dateTimeDetailsLoaded = false;
        TimeRequestBuilder getTimeRequest = new TimeRequestBuilder(layerId, selectedDate, proxyUrl
                + wmsUrl);
        getTimeRequest.setCallback(new TimeRequestCallback() {
            @Override
            public void onResponseReceived(Request request, Response response) {
                try {
                    super.onResponseReceived(request, response);
                    if (response.getStatusCode() != Response.SC_OK) {
                        throw new ConnectionException("Error contacting server");
                    }
                    availableTimesLoaded(layerId, getAvailableTimesteps(), nearestTime);
                    timeSelected(layerId, getWidgetCollection(layerId).getTimeSelector()
                            .getSelectedDateTime());
                    dateTimeDetailsLoaded = true;
                    updateMapBase(layerId);
                } catch (Exception e) {
                    invalidJson(e);
                } finally {
                    setLoading(false);
                }
            }

            @Override
            public void onError(Request request, Throwable exception) {
                setLoading(false);
                dateTimeDetailsLoaded = true;
                updateMapBase(layerId);
                handleError(exception);
            }
        });

        try {
            setLoading(true);
            getTimeRequest.send();
        } catch (RequestException e) {
            handleError(e);
        }
    }

    @Override
    public void timeSelected(String layerId, String selectedTime) {
        nearestTime = null;
        updateMapBase(layerId);
    }

    @Override
    public void onMapMove(MapMoveEvent eventObject) {
        /*
         * Do nothing. Subclasses may or may not want to.
         * 
         * We implement this here so that we receive the events and subclasses
         * don't *have* to bother with implementing and registering the callback
         */
    }

    @Override
    public void onMapZoom(MapZoomEvent eventObject) {
        /*
         * Do nothing. Subclasses may or may not want to.
         * 
         * We implement this here so that we receive the events and subclasses
         * don't *have* to bother with implementing and registering the callback
         */
    }

    public abstract void updateMap(MapArea mapArea, String layerUpdated);

    /**
     * This gets called once the page has loaded. Subclasses should use for
     * initializing any widgets, and setting the layout. If this is not
     * implemented, a blank page will be displayed
     */
    public abstract void init();

    /**
     * This is called once the menu details have been loaded. Subclasses should
     * use this to populate the appropriate widget(s)
     * 
     * @param menuTree
     *            the root {@link LayerMenuItem} of the menu tree
     */
    public abstract void menuLoaded(LayerMenuItem menuTree);

    /**
     * This is called once a layer's details have been loaded
     * 
     * @param layerDetails
     *            the details received from the server
     * @param autoUpdate
     *            whether or not we want to auto update palette and zoom
     */
    public abstract void layerDetailsLoaded(LayerDetails layerDetails, boolean autoUpdate);

    /**
     * This is called once a list of available times has been loaded
     * 
     * @param layerId
     *            the layer for which times have been loaded
     * @param availableTimes
     *            a {@link List} of available times
     * @param nearestTime
     *            the nearest time to the current time (for e.g. auto selection)
     */
    public abstract void availableTimesLoaded(String layerId, List<String> availableTimes,
            String nearestTime);

    /**
     * This is called when an auto scale range has been loaded. It can be
     * assumed that by this point we want to update the scale
     * 
     * @param layerId
     *            the layer for which the scale range has been loaded
     * @param min
     *            the minimum scale value
     * @param max
     *            the maximum scale value
     */
    public abstract void rangeLoaded(String layerId, double min, double max);

    /**
     * This is called when a loading process starts
     */
    public abstract void loadingStarted();

    /**
     * This is called when all loading processes have finished
     */
    public abstract void loadingFinished();

    /**
     * Gets the {@link GodivaWidgets} for the specified layer
     * 
     * @param layerId
     *            the layer ID
     * @return
     */
    public abstract GodivaWidgets getWidgetCollection(String layerId);
}