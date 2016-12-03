package com.moca.openfire;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.http.NameValuePair;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.jivesoftware.openfire.MessageRouter;
import org.jivesoftware.openfire.PresenceManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.util.XMPPDateTimeFormat;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.Presence;

import redis.clients.jedis.ShardedJedis;

/**
 * 摩擦OpenFire消息拦截处理插件
 * @Description 主要处理丢消息的情况，利用OpenFire离线机制，把所有发送的消息都存储到离线消息表，保证消息在网络不好的情况下不会丢失。客户端收到消息后发送消息回执，
 * 服务端收到该回执后把之前存储的离线消息删除掉，收不到回执等客户端上线后会自动当做离线消息发送。
 * 2014-07-29 修改 - 解决MySQL链接负担，增加Redis缓存层，继承OfflineMessageStore重写离线策略，将离线信息保存到Redis中。
 * @author JX
 */
public class MocaMsgPlugin implements PacketInterceptor, Plugin {

	private static final Logger log = LoggerFactory.getLogger(MocaMsgPlugin.class);
	private static PluginManager pluginManager;
	private UserManager userManager;
    private PresenceManager presenceManager;
	private static WriteLog writeLog = new WriteLog();
	/** 日志文件 */
	private static final String MOCA_TYPE_IQ_LOG = "/opt/openfire/logs/moca-type-iq.log";
	private static final String MOCA_TYPE_MESSAGE_LOG = "/opt/openfire/logs/moca-type-message.log";
	private static final String MOCA_PUSH_LOG = "/opt/openfire/logs/moca-push.log";
	private static final String MOCA_EXCEPTION_LOG = "/opt/openfire/logs/moca-exception.log";
	private static final String MOCA_REQUEST_LOG = "/opt/openfire/logs/moca-request.log";
	private static final String MOCA_RECEIVED_LOG = "/opt/openfire/logs/moca-received.log";
	private static final String MOCA_ORIGINAL_LOG = "/opt/openfire/logs/moca-original.log";

	public MocaMsgPlugin() {
		interceptorManager = InterceptorManager.getInstance();
		server = XMPPServer.getInstance();
	}

	private InterceptorManager interceptorManager;
	private XMPPServer server;

	@Override
	public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) throws PacketRejectedException {
		try {
			this.doAction(packet, incoming, processed, session);
		} catch (IOException e) {
			log.error("自定义日志写入异常: " + e.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private void doAction(Packet packet, boolean incoming, boolean processed, Session session) throws IOException {
		Packet copyPacket = packet.createCopy();
		Document doc = null;
		if (packet instanceof Message) {
			Message message = (Message) copyPacket;
			if (message.getType() == Message.Type.chat) { //需要跟踪的问题
//				log.info("单人聊天信息：", message.toXML());
				// 服务端只接收第一次请求的消息进行离线处理
				try {
					if (processed == true && incoming == false) {
						writeLog.fileWriter(MOCA_ORIGINAL_LOG, "ORIGINAL-ONLINE-原始消息: " + message.toXML() + " 时间:" + new Date());
						doc = DocumentHelper.parseText(message.toXML());
						Element rootElt = doc.getRootElement();
						String jid = "";
						String username = "";
						for (Iterator<Element> it = rootElt.elementIterator(); it.hasNext();) {
							Element element = (Element) it.next();
							writeLog.fileWriter(MOCA_ORIGINAL_LOG, element.getName() +"  --  "+ new Date());
							if (element.getName().equals("received")) {
								// 收到回执删除离线消息
								//String username = element.getParent().attributeValue("from").substring(0,element.getParent().attributeValue("from").indexOf("@"));
								//Redis离线策略
								OfflineMessageStoreExt.getInstance().deleteOffLineMessage(element.attributeValue("id"));
								writeLog.fileWriter(MOCA_RECEIVED_LOG, "RECEIVED-回执接收成功 messageId: " + element.attributeValue("id") + " 时间:" + new Date());
							} else if (element.getName().equals("request")) {
								// 含有request就添加离线消息
								jid = element.getParent().attributeValue("to");
								username = jid.substring(0,element.getParent().attributeValue("to").indexOf("@"));
//								OfflineMessageStoreExt.getInstance().addOffLineMessageValue(element.getParent().attributeValue("id"), message);
								OfflineMessageStoreExt.getInstance().addOffLineMessage(username, element.getParent().attributeValue("id"), message);
								writeLog.fileWriter(MOCA_REQUEST_LOG, "REQUEST-离线消息已添加 body: " + message.getBody() + "username: " + jid + " messageId: " + element.getParent().attributeValue("id") + " 时间:" + new Date());
								
							}
						}
					//离线推送
					} 
					else if (processed == false && incoming == true) {
						writeLog.fileWriter(MOCA_ORIGINAL_LOG, "ORIGINAL-OFFLINE-原始消息: " + message.toXML() + " 时间:" + new Date());
						doc = DocumentHelper.parseText(message.toXML());
						Element rootElt = doc.getRootElement();
						String jid = "";
						for (Iterator<Element> it = rootElt.elementIterator(); it.hasNext();) {
							Element element = (Element) it.next();
							if (element.getName().equals("request")) {
								jid = element.getParent().attributeValue("to");
								//用户离线的情况下发推送消息
								if(true) {
//									if(!isUserOnLine(jid)) {
									String from = element.getParent().attributeValue("from").substring(0,element.getParent().attributeValue("from").indexOf("@"));
									String to = element.getParent().attributeValue("to").substring(0,element.getParent().attributeValue("to").indexOf("@"));
							        sendPushMessage(from, to, message.getBody());
//							        System.out.println("------------------ offline push username : " + to + "  -------");
							        writeLog.fileWriter(MOCA_PUSH_LOG, "PUSH-离线推送成功 body: " + message.getBody() + " from: " + from + "to: " + to + " 时间:" + new Date());
								}
							}
						}
					}else{
						try{
//							String content= message.getBody();
//			                JID recipient = message.getTo();
//			                userManager = server.getUserManager();
//			                presenceManager = server.getPresenceManager();   
//							Presence status=presenceManager.getPresence(userManager.getUser(recipient.getNode()));
//			                if( status!=null ){
//			                	writeLog.fileWriter(MOCA_REQUEST_LOG, "REQUEST-online消息已添加 body: " + message.getBody() + " 时间:" + new Date());
//			                }else{
//			                	writeLog.fileWriter(MOCA_REQUEST_LOG, "REQUEST-offline消息已添加 body: " + message.getBody() + " 时间:" + new Date());
//			                }
						} catch (Exception ex) {
							writeLog.fileWriter(MOCA_TYPE_MESSAGE_LOG, "Message-dom4j 解析异常: " + ex.getMessage() + " 时间:" + new Date());
						}
					}
				} catch (DocumentException e) {
					writeLog.fileWriter(MOCA_TYPE_MESSAGE_LOG, "Message-dom4j 解析异常: " + e.getMessage() + " 时间:" + new Date());
				}
			} else if (message.getType() == Message.Type.groupchat) {
				List<?> els = message.getElement().elements("x");
				if (els != null && !els.isEmpty()) {
//					log.info("群聊天信息：", message.toXML());
				} else {
//					log.info("群系统信息：", message.toXML());
				}

			} else {
				try {
					writeLog.fileWriter(MOCA_RECEIVED_LOG, "其他消息 messageId: " + message.toXML() + " 时间:" + new Date());
					doc = DocumentHelper.parseText(message.toXML());
					Element rootElt = doc.getRootElement();
					String jid = "";
					String username = "";
					for (Iterator<Element> it = rootElt.elementIterator(); it.hasNext();) {
						Element element = (Element) it.next();
						writeLog.fileWriter(MOCA_ORIGINAL_LOG, element.getName() +"  --  "+ new Date());
						if (element.getName().equals("received")) {
							// 收到回执删除离线消息
							//String username = element.getParent().attributeValue("from").substring(0,element.getParent().attributeValue("from").indexOf("@"));
							//Redis离线策略
	//						System.out.println("------------------------------- received delete  message key : " + element.attributeValue("id") + "  ---------");
							OfflineMessageStoreExt.getInstance().deleteOffLineMessage(element.attributeValue("id"));
							writeLog.fileWriter(MOCA_RECEIVED_LOG, "RECEIVED-回执接收成功 messageId_1: " + element.attributeValue("id") + " 时间:" + new Date());
						}
					}
				} catch (DocumentException e) {
					writeLog.fileWriter(MOCA_TYPE_MESSAGE_LOG, "Message-dom4j 解析异常: " + e.getMessage() + " 时间:" + new Date());
				}
			}
		} else if (packet instanceof IQ) { //重发与该地方无关，注销掉还是会重发
			IQ iq = (IQ) copyPacket;
			ShardedJedis shardedJedis = ShardedJedisClient.getInstance().shardedJedis;
			if (iq.getType() == IQ.Type.set && iq.getChildElement() != null && "session".equals(iq.getChildElement().getName())) {
				if (processed == true && incoming == true) {
					String from = iq.getFrom().toString();
					String username = from.substring(0, from.indexOf("@"));
					writeLog.fileWriter(MOCA_TYPE_IQ_LOG, "IQ-用户登录成功: " + username + " 时间:" + new Date());
					//获取所有离线消息, 第二个参数：是否清除缓存，这里暂时保留，待收到回执一并清除。
					List<String> msgKeyList = OfflineMessageStoreExt.getInstance().getMessageKeyList(username);
					writeLog.fileWriter(MOCA_TYPE_IQ_LOG, "IQ-用户登录成功: " + msgKeyList.size()+"条消息！");
					if(msgKeyList.size() != 0) {
//						for (int in=0;in<msgKeyList.size();in++) {
						for (int in=msgKeyList.size()-1;in>=0;in--) {
							String msgKey = msgKeyList.get(in).toString();
//							for (String msgKey : msgKeyList) {
//							System.out.println("-------------------------------iq message key : " + msgKey + "  ---------");
							writeLog.fileWriter(MOCA_TYPE_IQ_LOG, "第"+in+"条: IQ-离线消息数量: " + msgKeyList.size() + " 用户: " + username + " 时间:" + new Date());
							try {
								String uid = "";
								String body = "";
								String jid = "";
								String stamp = "";
								//从缓存获取对应该key的离线消息
								if(null != shardedJedis.get(msgKey)) {
									doc = DocumentHelper.parseText(shardedJedis.get(msgKey));
//									System.out.println("-------------------------------iq message value : " + jedis.get(msgKey) + "  ---------");
									Element rootElt = doc.getRootElement();
									for (Iterator<Element> it = rootElt.elementIterator(); it.hasNext();) {
										Element element = (Element) it.next();
										if (element.getName().equals("body")) {
											uid = element.getParent().attributeValue("from");
											jid = element.getParent().attributeValue("to");
											body = element.getText();
										}
										if (element.getName().equals("delay")) {
											stamp = element.attributeValue("stamp");
											System.out.println("------------------------------- stamp : " + stamp + "  ---------");
										}
									}
									MessageRouter messageRouter = server.getMessageRouter();
									Message message = new Message();
									message.setBody(body);
									message.setFrom(uid);
									message.setTo(jid);
									message.setType(Message.Type.chat);
									message.setID(msgKey);
									Element delay = message.addChildElement("delay", "urn:xmpp:delay");
					                delay.addAttribute("from", XMPPServer.getInstance().getServerInfo().getXMPPDomain());
					                delay.addAttribute("stamp", XMPPDateTimeFormat.format(new Date(Long.parseLong(stamp))));
									messageRouter.route(message); 
									writeLog.fileWriter(MOCA_TYPE_IQ_LOG, "IQ-消息拉取成功 " + message.toXML() + " 时间:" + new Date());
								}
							} catch (DocumentException e) {
//								log.info("用户登录成功，离线消息发送失败：", username);
								writeLog.fileWriter(MOCA_TYPE_IQ_LOG, "IQ-dom4j 解析异常: " + e.getMessage() + ", 导致离线消息发送失败：" + username + " 时间:" + new Date());
							}
							//删除messageKey的缓存消息
							OfflineMessageStoreExt.getInstance().deleteOffLineMessage(msgKey);
							writeLog.fileWriter(MOCA_TYPE_IQ_LOG, "IQ-删除离线消息  msgKey: " + msgKey + " 时间:" + new Date());
//							System.out.println("-------------------------------iq  delete one message key : " + msgKey + "  ---------");
						}
						//清空该用户的所有离线消息缓存
						OfflineMessageStoreExt.getInstance().deleteOffLineMessage(username);
						writeLog.fileWriter(MOCA_TYPE_IQ_LOG, "IQ-清空离线消息 username: " + username + " 时间:" + new Date());
//						System.out.println("-------------------------------iq  delete all message username : " + username + "  ---------");
					}
					//创建新日志
//					writeLog.createNewLog(MOCA_TYPE_IQ_LOG);
//					writeLog.createNewLog(MOCA_TYPE_MESSAGE_LOG);
//					writeLog.createNewLog(MOCA_PUSH_LOG);
//					writeLog.createNewLog(MOCA_EXCEPTION_LOG);
//					writeLog.createNewLog(MOCA_REQUEST_LOG);
//					writeLog.createNewLog(MOCA_RECEIVED_LOG);
//					writeLog.createNewLog(MOCA_ORIGINAL_LOG);
				}
			}
		} else if (packet instanceof Presence) {
			Presence presence = (Presence) copyPacket;
			if (presence.getType() == Presence.Type.unavailable) {
//				log.info("用户退出服务器成功：", presence.toXML());
//				System.out.println("用户退出服务器成功：" + presence.toXML());
			}
		}
	}
	
	/**
	 * 判断用户是否在线
	 * @param strUrl http://product.gzdemi.com:9090/plugins/presence/status?jid=test2@im.himoca.com&type=xml
	 * @throws IOException 
	 */
	public boolean isUserOnLine(String jid) throws IOException {
		String strUrl = "http://localhost:9090/plugins/presence/status?jid="+jid+"&type=xml";
        //返回值 : 0 - 用户不存在; 1 - 用户在线; 2 - 用户离线 
        try {
            URL oUrl = new URL(strUrl);
            URLConnection oConn = oUrl.openConnection();
            if (oConn != null) {
                BufferedReader oIn = new BufferedReader(new   InputStreamReader(oConn.getInputStream()));
                if (null != oIn) {
                    String strFlag = oIn.readLine();
                    oIn.close();
                    if (strFlag.indexOf("type=\"unavailable\"") >= 0) {
                        return false;
                    }
                    if (strFlag.indexOf("type=\"error\"") >= 0) {
                        return false;
                    } else if (strFlag.indexOf("priority") >= 0 || strFlag.indexOf("id=\"") >= 0) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
        	writeLog.fileWriter(MOCA_EXCEPTION_LOG, " isUserOnLine 判断用户是否在线异常: " + e.getMessage() + " 时间:" + new Date());
        }
        return false;
	}
	
	/**
	 * 发推送消息
	 * @param from
	 * @param to
	 * @param content
	 * @throws IOException 
	 */
	public void sendPushMessage(final String from, final String to, final String content) throws IOException {
		Thread pustJob = new Thread(new Runnable() {
		     public void run() {
		    	 try{
					try {
						HttpClient httpclient = new DefaultHttpClient();
						JSONObject jsonObj = new JSONObject(content);
						String realContent = (String)jsonObj.get("M");
						String realNickname = (String)jsonObj.get("N");
			
						JSONObject requestBody = new JSONObject();
						requestBody.put("sendUserid",from);
						requestBody.put("title","得米");
						requestBody.put("content",realNickname+":"+realContent);
						requestBody.put("userid",to);
					    String base64Str = new String(org.bouncycastle.util.encoders.Base64.encode(requestBody.toString().getBytes()));
					    String urlStr = "http://testpush.gzdemi.com:8081/xinGeRestapiController/service.do";
					   
					    writeLog.fileWriter(MOCA_PUSH_LOG, "PUSH-推送消息: "+ from + " " + to + " " + base64Str + requestBody.toString() + " 时间:" + new Date());
					    
					    HttpPost httppost = new HttpPost(urlStr);
					    List<NameValuePair> params = new ArrayList<NameValuePair>(2);
					    params.add(new BasicNameValuePair("action", "pushMessageToUser"));
					    params.add(new BasicNameValuePair("params", base64Str));
					    httppost.setEntity(new UrlEncodedFormEntity(params,"UTF-8"));
					    httpclient.execute(httppost);
					} catch (ClientProtocolException e) {
						writeLog.fileWriter(MOCA_PUSH_LOG, " PUSH-ClientProtocolException 异常: " + e.getMessage() + " 时间:" + new Date());
					} catch (IOException e) {
						writeLog.fileWriter(MOCA_PUSH_LOG, " PUSH-IOException 异常: " + e.getMessage() + " 时间:" + new Date());
					} catch (JSONException e) {
						writeLog.fileWriter(MOCA_PUSH_LOG, " PUSH-JSONException 异常: " + e.getMessage() + " 时间:" + new Date());
					}
		    	 }catch(IOException e){
		    		 e.printStackTrace();
		    	 }
		     }
		 });  
		pustJob.start();
	}
	
	@SuppressWarnings("unused")
	private void debug(Packet packet, boolean incoming, boolean processed, Session session) {
		String info = "[ packetID: " + packet.getID() + ", to: " + packet.getTo() + ", from: " + packet.getFrom() + ", incoming: " + incoming + ", processed: " + processed + " ]";
		long timed = System.currentTimeMillis();
		debug("------------------- start -------------------" + timed);
		debug("id:" + session.getStreamID() + ", address: " + session.getAddress());
		debug("info: " + info);
		debug("xml: " + packet.toXML());
		debug("-------------------  end  -------------------" + timed);
		log.info("id:" + session.getStreamID() + ", address: " + session.getAddress());
		log.info("info: {}", info);
		log.info("plugin Name: " + pluginManager.getName(this) + ", xml: " + packet.toXML());
	}

	private void debug(Object message) {
		if (true) {
			System.out.println(message);
		}
	}

	public void initializePlugin(PluginManager manager, File pluginDirectory) {
		interceptorManager.addInterceptor(this);
		pluginManager = manager;
		System.out.println("initializing... install plugin!");
	}

	public void destroyPlugin() {
		interceptorManager.removeInterceptor(this);
		System.out.println("server stop，destroy plugin!");
	}
	
	public static String convertStreamToString(InputStream is) {    
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));    
        StringBuilder sb = new StringBuilder();    
     
        String line = null;    
        try {    
            while ((line = reader.readLine()) != null) {
                sb.append(line + "\n");    
            }    
        } catch (IOException e) {    
            e.printStackTrace();    
        } finally {    
            try {    
                is.close();    
            } catch (IOException e) {    
               e.printStackTrace();    
            }    
        }    
        return sb.toString();    
    }
	
}