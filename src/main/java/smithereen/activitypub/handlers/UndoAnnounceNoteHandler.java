package smithereen.activitypub.handlers;

import java.sql.SQLException;

import smithereen.activitypub.ActivityHandlerContext;
import smithereen.activitypub.NestedActivityTypeHandler;
import smithereen.activitypub.objects.activities.Announce;
import smithereen.activitypub.objects.activities.Undo;
import smithereen.data.ForeignUser;
import smithereen.data.Post;
import smithereen.data.feed.NewsfeedEntry;
import smithereen.data.notifications.Notification;
import smithereen.storage.NewsfeedStorage;
import smithereen.storage.NotificationsStorage;

public class UndoAnnounceNoteHandler extends NestedActivityTypeHandler<ForeignUser, Undo, Announce, Post>{
	@Override
	public void handle(ActivityHandlerContext context, ForeignUser actor, Undo activity, Announce nested, Post post) throws SQLException{
		context.appContext.getNewsfeedController().deleteFriendsFeedEntry(actor, post.id, NewsfeedEntry.Type.RETOOT);
		NotificationsStorage.deleteNotification(Notification.ObjectType.POST, post.id, Notification.Type.RETOOT, actor.id);
	}
}
