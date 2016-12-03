package com.moca.openfire;

import java.util.ArrayList;
import java.util.List;

import org.jivesoftware.openfire.OfflineMessageStore;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import redis.clients.jedis.ShardedJedis;

/**
 * OfflineMessageStoreExt
 * 
 * @Description 解决MySQL链接负担，增加Redis缓存层，继承OfflineMessageStore重写离线策略，
 *              将离线信息保存到Redis中。
 * @author JX
 */
public class OfflineMessageStoreExt {

	private static final Logger Log = LoggerFactory.getLogger(OfflineMessageStoreExt.class);

	private static OfflineMessageStoreExt instance = null;

	public static OfflineMessageStoreExt getInstance() {
		if (instance == null)
			instance = new OfflineMessageStoreExt();
		return instance;
	}

	/**
	 * 修改离线消息缓存策略，利用Redis List数据格式存储key = username； value = messageKey.
	 * 
	 * @param username
	 * @param messageKey
	 * @param message
	 */
	public void addOffLineMessage(String username, String messageKey, Message message) {
		if (message == null) {
			return;
		}
		if (message.getBody() == null || message.getBody().length() == 0) {
			if (message.getChildElement("event", "http://jabber.org/protocol/pubsub#event") == null) {
				return;
			}
		}
		JID recipient = message.getTo();
		if (username == null || !UserManager.getInstance().isRegisteredUser(recipient)) {
			return;
		} else if (!XMPPServer.getInstance().getServerInfo().getXMPPDomain().equals(recipient.getDomain())) {
			return;
		}

		ShardedJedis shardedJedis = null;
		try {
			shardedJedis = ShardedJedisClient.getInstance().shardedJedis;
			if(!shardedJedis.exists(messageKey)){
				shardedJedis.lpush(username, messageKey);
				Log.info("add offline messages of messageKey: " + messageKey+" "+username);
				// 添加离线Delay消息时间
				message.addChildElement("delay", "urn:xmpp:delay")
						.addAttribute("from", XMPPServer.getInstance().getServerInfo().getXMPPDomain())
						.addAttribute("stamp", StringUtils.dateToMillis(new java.util.Date()));
	
				shardedJedis.set(messageKey, message.toXML());
			}
		}

		catch (Exception e) {
			Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
		} finally {
			ShardedJedisClient.getInstance().destroy();
		}

	}


	/**
	 * 返回该用户名下的所有消息ID
	 * 
	 * @param username
	 * @return messageId
	 */
	public List<String> getMessageKeyList(String username) {
		List<String> msgKeyList = new ArrayList<String>();
		ShardedJedis shardedJedis = null;
		try {
			shardedJedis = ShardedJedisClient.getInstance().shardedJedis;
			msgKeyList = shardedJedis.lrange(username, 0, -1);
		} catch (Exception e) {
			Log.error("Error retrieving offline messages of username: " + username, e);
		} finally {
			ShardedJedisClient.getInstance().destroy();
		}
		return msgKeyList;
	}

	public void deleteOffLineMessage(String messageKey) {
		ShardedJedis shardedJedis = null;
		try {
			shardedJedis = ShardedJedisClient.getInstance().shardedJedis;
			Log.info("del offline messages of messageKey: " + messageKey);
			shardedJedis.del(messageKey);
		} catch (Exception e) {
			Log.error("Error deleting offline message of messageKey: " + messageKey, e);
		} finally {
			ShardedJedisClient.getInstance().destroy();
		}
	}

}
