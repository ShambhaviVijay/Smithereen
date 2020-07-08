package smithereen;

import org.jtwig.JtwigModel;
import org.jtwig.environment.EnvironmentConfiguration;
import org.jtwig.environment.EnvironmentConfigurationBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;

import smithereen.data.Account;
import smithereen.data.ForeignUser;
import smithereen.data.SessionInfo;
import smithereen.data.User;
import smithereen.jtwigext.LangDateFunction;
import smithereen.jtwigext.LangFunction;
import smithereen.jtwigext.LangGenderedFunction;
import smithereen.jtwigext.LangPluralFunction;
import smithereen.jtwigext.NumberSequenceFunction;
import smithereen.jtwigext.PhotoSizeFunction;
import smithereen.jtwigext.PictureForAvatarFunction;
import smithereen.jtwigext.RenderAttachmentsFunction;
import smithereen.routes.ActivityPubRoutes;
import smithereen.routes.NotificationsRoutes;
import smithereen.routes.PostRoutes;
import smithereen.routes.ProfileRoutes;
import smithereen.routes.SessionRoutes;
import smithereen.routes.SettingsAdminRoutes;
import smithereen.routes.SystemRoutes;
import smithereen.routes.WellKnownRoutes;
import smithereen.storage.DatabaseSchemaUpdater;
import smithereen.storage.SessionStorage;
import smithereen.routes.SettingsRoutes;
import smithereen.storage.UserStorage;
import spark.Request;
import spark.Response;
import spark.utils.StringUtils;

import static spark.Spark.*;
import static smithereen.sparkext.SparkExtension.*;

public class Main{

	public static final EnvironmentConfiguration jtwigEnv;

	static{
		jtwigEnv=EnvironmentConfigurationBuilder.configuration()
				.functions()
					.add(new LangFunction())
					.add(new LangPluralFunction())
					.add(new LangDateFunction())
					.add(new LangGenderedFunction())
					.add(new PictureForAvatarFunction())
					.add(new RenderAttachmentsFunction())
					.add(new PhotoSizeFunction())
					.add(new NumberSequenceFunction())
				.and()
				.build();
	}

	public static void main(String[] args){
		if(args.length==0){
			System.err.println("You need to specify the path to the config file as the first argument:\njava -jar smithereen.jar config.properties");
			System.exit(1);
		}

		try{
			Config.load(args[0]);
			Config.loadFromDatabase();
			DatabaseSchemaUpdater.maybeUpdate();
		}catch(IOException|SQLException x){
			throw new RuntimeException(x);
		}

		if(args.length>1){
			if(args[1].equalsIgnoreCase("init_admin")){
				CLI.initializeAdmin();
			}else{
				System.err.println("Unknown argument: '"+args[1]+"'");
				System.exit(1);
			}
			return;
		}

		ActivityPubRoutes.registerActivityHandlers();

		ipAddress(Config.serverIP);
		port(Config.serverPort);
		if(Config.staticFilesPath!=null)
			externalStaticFileLocation(Config.staticFilesPath);
		else
			staticFileLocation("/public");
		staticFiles.expireTime(7*24*60*60);
		before((request, response) -> {
			request.attribute("start_time", System.currentTimeMillis());
			if(request.session(false)==null || request.session().attribute("info")==null){
				String psid=request.cookie("psid");
				if(psid!=null){
					if(!SessionStorage.fillSession(psid, request.session(true), request)){
						response.removeCookie("/", "psid");
					}else{
						response.cookie("/", "psid", psid, 10*365*24*60*60, false);
					}
				}
			}
			SessionInfo info=Utils.sessionInfo(request);
			if(info!=null && info.account!=null){
				info.account=UserStorage.getAccount(info.account.id);
			}
//			String hs="";
//			for(String h:request.headers())
//				hs+="["+h+": "+request.headers(h)+"] ";
//			System.out.println(request.requestMethod()+" "+request.raw().getPathInfo()+" "+hs);
			if(request.pathInfo().startsWith("/activitypub")){
				request.attribute("templateDir", "popup");
			}
		});

		get("/", Main::indexPage);

		getLoggedIn("/feed", PostRoutes::feed);

		path("/account", ()->{
			post("/login", SessionRoutes::login);
			get("/login", SessionRoutes::login);
			get("/logout", SessionRoutes::logout);
			post("/register", SessionRoutes::register);
			get("/register", SessionRoutes::registerForm);
		});

		path("/settings", ()->{
			path("/profile", ()->{
				getLoggedIn("/general", SettingsRoutes::profileEditGeneral);
			});
			getLoggedIn("/", SettingsRoutes::settings);
			postWithCSRF("/createInvite", SettingsRoutes::createInvite);
			postWithCSRF("/updatePassword", SettingsRoutes::updatePassword);
			postWithCSRF("/updateProfileGeneral", SettingsRoutes::updateProfileGeneral);
			postWithCSRF("/updateProfilePicture", SettingsRoutes::updateProfilePicture);
			postWithCSRF("/removeProfilePicture", SettingsRoutes::removeProfilePicture);
			getLoggedIn("/confirmRemoveProfilePicture", SettingsRoutes::confirmRemoveProfilePicture);
			post("/setLanguage", SettingsRoutes::setLanguage);
			post("/setTimezone", SettingsRoutes::setTimezone);

			path("/admin", ()->{
				getRequiringAccessLevel("", Account.AccessLevel.ADMIN, SettingsAdminRoutes::index);
				postRequiringAccessLevelWithCSRF("/updateServerInfo", Account.AccessLevel.ADMIN, SettingsAdminRoutes::updateServerInfo);
				getRequiringAccessLevel("/users", Account.AccessLevel.ADMIN, SettingsAdminRoutes::users);
				getRequiringAccessLevel("/users/accessLevelForm", Account.AccessLevel.ADMIN, SettingsAdminRoutes::accessLevelForm);
				postRequiringAccessLevelWithCSRF("/users/setAccessLevel", Account.AccessLevel.ADMIN, SettingsAdminRoutes::setUserAccessLevel);
			});
		});

		path("/activitypub", ()->{
			post("/sharedInbox", ActivityPubRoutes::sharedInbox);
			getLoggedIn("/externalInteraction", ActivityPubRoutes::externalInteraction);
			get("/nodeinfo/2.0", ActivityPubRoutes::nodeInfo);
			path("/objects", ()->{
				path("/likes/:likeID", ()->{
					get("", ActivityPubRoutes::likeObject);
					get("/undo", ActivityPubRoutes::undoLikeObject);
				});
			});
		});

		path("/.well-known", ()->{
			get("/webfinger", WellKnownRoutes::webfinger);
			get("/nodeinfo", WellKnownRoutes::nodeInfo);
		});

		path("/system", ()->{
			get("/downloadExternalMedia", SystemRoutes::downloadExternalMedia);
			getWithCSRF("/deleteDraftAttachment", SystemRoutes::deleteDraftAttachment);
			path("/upload", ()->{
				postWithCSRF("/postPhoto", SystemRoutes::uploadPostPhoto);
			});
		});

		path("/users/:id", ()->{
			get("", "application/activity+json", ActivityPubRoutes::userActor);
			get("", "application/ld+json", ActivityPubRoutes::userActor);
			get("", (req, resp)->{
				int id=Utils.parseIntOrDefault(req.params(":id"), 0);
				User user=UserStorage.getById(id);
				if(user==null || user instanceof ForeignUser){
					resp.status(404);
				}else{
					resp.redirect("/"+user.username);
				}
				return "";
			});

			post("/inbox", ActivityPubRoutes::inbox);
			get("/outbox", ActivityPubRoutes::outbox);
			post("/outbox", (req, resp)->{
				resp.status(405);
				return "";
			});
			get("/followers", ActivityPubRoutes::userFollowers);
			get("/following", ActivityPubRoutes::userFollowing);
		});

		path("/posts/:postID", ()->{
			get("", "application/activity+json", ActivityPubRoutes::post);
			get("", "application/ld+json", ActivityPubRoutes::post);
			get("", PostRoutes::standalonePost);

			getLoggedIn("/confirmDelete", PostRoutes::confirmDelete);
			postWithCSRF("/delete", PostRoutes::delete);

			getWithCSRF("/like", PostRoutes::like);
			getWithCSRF("/unlike", PostRoutes::unlike);
			get("/likePopover", PostRoutes::likePopover);
			get("/likes", PostRoutes::likeList);

			get("/replies", ActivityPubRoutes::postReplies);
		});

		get("/robots.txt", (req, resp)->{
			resp.type("text/plain");
			return "";
		});

		path("/my", ()->{
			getLoggedIn("/incomingFriendRequests", ProfileRoutes::incomingFriendRequests);
			get("/friends", ProfileRoutes::friends);
			get("/followers", ProfileRoutes::followers);
			get("/following", ProfileRoutes::following);
			getLoggedIn("/notifications", NotificationsRoutes::notifications);
		});

		path("/:username", ()->{
			get("", "application/activity+json", ActivityPubRoutes::userActor);
			get("", "application/ld+json", ActivityPubRoutes::userActor);
			get("", ProfileRoutes::profile);
			postWithCSRF("/createWallPost", PostRoutes::createWallPost);

			postWithCSRF("/remoteFollow", ActivityPubRoutes::remoteFollow);

			getLoggedIn("/confirmSendFriendRequest", ProfileRoutes::confirmSendFriendRequest);
			postWithCSRF("/doSendFriendRequest", ProfileRoutes::doSendFriendRequest);
			postWithCSRF("/respondToFriendRequest", ProfileRoutes::respondToFriendRequest);
			postWithCSRF("/doRemoveFriend", ProfileRoutes::doRemoveFriend);
			getLoggedIn("/confirmRemoveFriend", ProfileRoutes::confirmRemoveFriend);
			get("/friends", ProfileRoutes::friends);
			get("/followers", ProfileRoutes::followers);
			get("/following", ProfileRoutes::following);
		});


		exception(ObjectNotFoundException.class, (x, req, resp)->{
			resp.status(404);
			resp.body("Not found");
		});
		exception(BadRequestException.class, (x, req, resp)->{
			resp.status(400);
			String msg=x.getMessage();
			if(StringUtils.isNotEmpty(msg))
				resp.body("Bad request: "+msg.replace("<", "&lt;"));
			else
				resp.body("Bad request");
		});
		exception(Exception.class, (exception, req, res) -> {
			System.out.println("Exception while processing "+req.requestMethod()+" "+req.raw().getPathInfo());
			exception.printStackTrace();
			res.status(500);
			StringWriter sw=new StringWriter();
			exception.printStackTrace(new PrintWriter(sw));
			res.body("<h1 style='color: red;'>Unhandled exception</h1><pre>"+sw.toString().replace("<", "&lt;")+"</pre>");
		});

		after((req, resp)->{
			Long l=req.attribute("start_time");
			if(l!=null){
				long t=(long)l;
				resp.header("X-Generated-In", (System.currentTimeMillis()-t)+"");
			}
			if(req.attribute("isTemplate")!=null){
				resp.header("Link", "</res/style.css?"+Utils.staticFileHash+">; rel=preload; as=style, </res/common.js?"+Utils.staticFileHash+">; rel=preload; as=script");
			}

			if(req.headers("accept")==null || !req.headers("accept").startsWith("application/")){
				try{
					if(req.session().attribute("info")==null)
						req.session().attribute("info", new SessionInfo());
					if(req.requestMethod().equalsIgnoreCase("get") && req.attribute("noHistory")==null){
						SessionInfo info=req.session().attribute("info");
						String path=req.pathInfo();
						String query=req.raw().getQueryString();
						if(StringUtils.isNotEmpty(query)){
							path+='?'+query;
						}
						info.history.add(path);
					}
				}catch(Throwable ignore){}
			}
		});
	}

	private static Object indexPage(Request req, Response resp){
		SessionInfo info=Utils.sessionInfo(req);
		if(info!=null && info.account!=null){
			resp.redirect("/feed");
			return "";
		}
		JtwigModel model=JtwigModel.newModel().with("title", Config.serverDisplayName)
				.with("signupMode", Config.signupMode)
				.with("serverDisplayName", Config.serverDisplayName)
				.with("serverDescription", Config.serverDescription);
		return Utils.renderTemplate(req, "index", model);
	}
}
