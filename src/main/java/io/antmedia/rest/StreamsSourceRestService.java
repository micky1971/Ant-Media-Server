package io.antmedia.rest;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.StringUtils;
import org.red5.server.api.scope.IScope;
import org.red5.server.util.ScopeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.IDataStore;
import io.antmedia.datastore.db.MapDBStore;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.datastore.db.types.Vod;
import io.antmedia.ipcamera.OnvifCamera;
import io.antmedia.ipcamera.onvifdiscovery.OnvifDiscovery;
import io.antmedia.rest.model.Result;
import io.antmedia.streamsource.StreamFetcher;
import io.antmedia.streamsource.StreamFetcherManager;

@Component
@Path("/streamSource")
public class StreamsSourceRestService {

	@Context
	private ServletContext servletContext;

	private IDataStore dbStore;
	private ApplicationContext appCtx;

	private IScope scope;

	private AntMediaApplicationAdapter appInstance;

	protected static Logger logger = LoggerFactory.getLogger(StreamsSourceRestService.class);




	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/addStreamSource")
	@Produces(MediaType.APPLICATION_JSON)
	public Result addStreamSource(Broadcast stream) {
		Result result=new Result(false);

		logger.info("username {}", stream.getUsername());
		logger.info("pass {}", stream.getPassword());


		if (stream.getName() != null && stream.getName().length() > 0 && checkStreamUrl(stream.getStreamUrl())) {

			if (stream.getType().equals(AntMediaApplicationAdapter.IP_CAMERA)) {

				OnvifCamera onvif = new OnvifCamera();
				onvif.connect(stream.getIpAddr(), stream.getUsername(), stream.getPassword());
				String rtspURL = onvif.getRTSPStreamURI();

				if (rtspURL != "no") {

					String authparam = stream.getUsername() + ":" + stream.getPassword() + "@";
					String rtspURLWithAuth = "rtsp://" + authparam + rtspURL.substring("rtsp://".length());
					logger.info("rtsp url with auth: {}", rtspURLWithAuth);
					stream.setStreamUrl(rtspURLWithAuth);
					Date currentDate = new Date();
					long unixTime = currentDate.getTime();

					stream.setDate(unixTime);
					stream.setStatus(AntMediaApplicationAdapter.BROADCAST_STATUS_CREATED);

					String id = getStore().save(stream);


					if (id.length() > 0) {
						Broadcast newCam = getStore().get(stream.getStreamId());
						StreamFetcher streamFetcher = getInstance().startStreaming(newCam);
						if (streamFetcher != null) {
							result.setSuccess(true);
						}
						else {
							getStore().delete(stream.getStreamId());
						}
					}
					onvif.disconnect();

				}

			}
			else if (stream.getType().equals(AntMediaApplicationAdapter.STREAM_SOURCE)) {

				Date currentDate = new Date();
				long unixTime = currentDate.getTime();

				stream.setDate(unixTime);
				stream.setStatus(AntMediaApplicationAdapter.BROADCAST_STATUS_CREATED);

				String id = getStore().save(stream);

				if (id.length() > 0) {
					Broadcast newSource = getStore().get(stream.getStreamId());
					getInstance().startStreaming(newSource);
				}

				result.setSuccess(true);
				result.setMessage(id);

			}else {

				result.setMessage("No stream added");

			}
		}

		return result;
	}



	@GET
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/getCameraError")
	@Produces(MediaType.APPLICATION_JSON)
	public Result getCameraError(@QueryParam("id") String id) {
		Result result = new Result(true);

		for (StreamFetcher camScheduler : getInstance().getStreamFetcherManager().getStreamFetcherList()) {
			if (camScheduler.getStream().getIpAddr().equals(id)) {
				result = camScheduler.getCameraError();
			}
		}

		return result;
	}


	@GET
	@Path("/synchUserVoDList")
	@Produces(MediaType.APPLICATION_JSON)
	public Result synchUserVodList() {
		boolean result = false;
		int errorId = -1;
		String message = "";

		String vodFolder = getInstance().getAppSettings().getVodFolder();

		logger.info("synch user vod list vod folder is {}", vodFolder);

		if (vodFolder != null && vodFolder.length() > 0) {
			result = getInstance().synchUserVoDFolder(null, vodFolder);
		}
		else {
			errorId = 404;
			message = "no vod folder defined";
		}

		return new Result(result, message, errorId);
	}




	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/updateCamInfo")
	@Produces(MediaType.APPLICATION_JSON)
	public Result updateCamInfo(Broadcast broadcast) {
		boolean result = false;
		OnvifCamera onvif = null;
		logger.warn("inside of rest service");

		if( checkStreamUrl(broadcast.getStreamUrl()) && broadcast.getStatus()!=null){

			getInstance().stopStreaming(broadcast);
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
				Thread.currentThread().interrupt();
			}
			if(broadcast.getType().equals(AntMediaApplicationAdapter.IP_CAMERA)) {

				onvif = new OnvifCamera();
				onvif.connect(broadcast.getIpAddr(), broadcast.getUsername(), broadcast.getPassword());
				String rtspURL = onvif.getRTSPStreamURI();

				if (rtspURL != "no") {

					String authparam = broadcast.getUsername() + ":" + broadcast.getPassword() + "@";
					String rtspURLWithAuth = "rtsp://" + authparam + rtspURL.substring("rtsp://".length());
					logger.info("new RTSP URL: {}" , rtspURLWithAuth);
					broadcast.setStreamUrl(rtspURLWithAuth);
				}
			}

			if (onvif != null) {
				onvif.disconnect();
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				logger.error(e.getMessage());
				Thread.currentThread().interrupt();
			}

			result = getStore().editStreamSourceInfo(broadcast);
			getInstance().startStreaming(broadcast);
		}
		return new Result(result);
	}

	@GET
	@Path("/searchOnvifDevices")
	@Produces(MediaType.APPLICATION_JSON)
	public String[] searchOnvifDevices() {

		String localIP = null;
		String[] list = null;
		Enumeration<NetworkInterface> interfaces = null;
		try {
			interfaces = NetworkInterface.getNetworkInterfaces();
		} catch (SocketException e) {
			// handle error
		}

		if (interfaces != null) {
			while (interfaces.hasMoreElements()) {
				NetworkInterface i = interfaces.nextElement();
				Enumeration<InetAddress> addresses = i.getInetAddresses();
				while (addresses.hasMoreElements() && (localIP == null || localIP.isEmpty())) {
					InetAddress address = addresses.nextElement();
					if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
						localIP = address.getHostAddress();
					}
				}
			}
			logger.warn("IP Address: {} " , localIP);
		}

		if (localIP != null) {

			String[] ipAddrParts = localIP.split("\\.");

			String ipAd = ipAddrParts[0] + "." + ipAddrParts[1] + "." + ipAddrParts[2] + ".";

			logger.warn("inside of auto discovery ip Addr {}", ipAd);

			ArrayList<String> addressList = new ArrayList<>();

			for (int i = 2; i < 255; i++) {
				addressList.add(ipAd + i);

			}

			List<URL> onvifDevices = OnvifDiscovery.discoverOnvifDevices(true, addressList);

			list = new String[onvifDevices.size()];

			if (!onvifDevices.isEmpty()) {

				for (int i = 0; i < onvifDevices.size(); i++) {

					list[i] = StringUtils.substringBetween(onvifDevices.get(i).toString(), "http://", "/");
				}
			}

		}

		return list;
	}

	@GET
	@Path("/moveUp")
	@Produces(MediaType.APPLICATION_JSON)
	public Result moveUp(@QueryParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getInstance().getOnvifCamera(id);
		if (camera != null) {
			camera.MoveUp();
			result = true;
		}
		return new Result(result);
	}

	@GET
	@Path("/moveDown")
	@Produces(MediaType.APPLICATION_JSON)
	public Result moveDown(@QueryParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getInstance().getOnvifCamera(id);
		if (camera != null) {
			camera.MoveDown();
			result = true;
		}
		return new Result(result);
	}

	@GET
	@Path("/moveLeft")
	@Produces(MediaType.APPLICATION_JSON)
	public Result moveLeft(@QueryParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getInstance().getOnvifCamera(id);
		if (camera != null) {
			camera.MoveLeft();
			result = true;
		}
		return new Result(result);
	}

	@GET
	@Path("/moveRight")
	@Produces(MediaType.APPLICATION_JSON)
	public Result moveRight(@QueryParam("id") String id) {
		boolean result = false;
		OnvifCamera camera = getInstance().getOnvifCamera(id);
		if (camera != null) {
			camera.MoveRight();
			result = true;
		}
		return new Result(result);
	}

	@Nullable
	private ApplicationContext getAppContext() {
		if (servletContext != null) {
			appCtx = (ApplicationContext) servletContext
					.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
		}
		return appCtx;
	}

	public AntMediaApplicationAdapter getInstance() {
		if (appInstance == null) {
			appInstance = (AntMediaApplicationAdapter) getAppContext().getBean("web.handler");
		}
		return appInstance;
	}

	public IScope getScope() {
		if (scope == null) {
			scope = getInstance().getScope();
		}
		return scope;
	}

	public IDataStore getStore() {
		if (dbStore == null) {
			WebApplicationContext ctxt = WebApplicationContextUtils.getWebApplicationContext(servletContext);
			dbStore = (IDataStore) ctxt.getBean("db.datastore");
		}
		return dbStore;
	}

	public void setCameraStore(MapDBStore cameraStore) {
		this.dbStore = cameraStore;
	}
	public boolean validateIPaddress(String ipaddress)  {

		final String IPV4_REGEX = "(([0-1]?[0-9]{1,2}\\.)|(2[0-4][0-9]\\.)|(25[0-5]\\.)){3}(([0-1]?[0-9]{1,2})|(2[0-4][0-9])|(25[0-5]))";
		Pattern pattern = Pattern.compile(IPV4_REGEX);

		return pattern.matcher(ipaddress).matches();

	}

	public boolean checkStreamUrl (String url) {

		logger.info("inside check url {}", url);

		boolean streamUrlControl = false;
		String[] ipAddrParts = null;
		String ipAddr = null;

		if(url != null && (url.startsWith("http://") ||
								url.startsWith("https://") ||
								url.startsWith("rtmp://") ||
								url.startsWith("rtmps://") ||
								url.startsWith("rtsp://"))) 
		{
			streamUrlControl=true;
			ipAddrParts = url.split("//");
			ipAddr = ipAddrParts[1];

			if (ipAddr.contains("@")){

				ipAddrParts = ipAddr.split("@");
				ipAddr = ipAddrParts[1];

			}
			if (ipAddr.contains(":")){

				ipAddrParts = ipAddr.split(":");
				ipAddr = ipAddrParts[0];

			}
			if (ipAddr.contains("/")){

				ipAddrParts = ipAddr.split("/");
				ipAddr = ipAddrParts[0];

			}
			if(ipAddr.split(".").length == 4 && !this.validateIPaddress(ipAddr)){
				streamUrlControl = false;
			}
		}
		return streamUrlControl;

	}


}
