package com.moca.openfire;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

public class HttpUtils {
	public final static String sendGet = "GET";
	public final static String sendPost = "POST";

	/****
	 * 发生请求
	 * 
	 * @param url
	 *            请求地址
	 * @param sendType
	 *            请求类型（post ，get）
	 * @param params
	 *            请求参数（map 键值对形式）
	 * @return
	 * @throws IOException
	 */
	public static String sendRequest(String url, String sendType, Map<String, String> params) throws IOException {
		StringBuffer response = new StringBuffer("");
		// 默认post请求
		String requestMethod = sendPost;
		// 请求类型
		if (sendType != null && sendGet.equals(sendType.toUpperCase())) {
			requestMethod = sendGet;
		}
		// 拼接参数
		StringBuilder tokenUri = new StringBuilder("");
		int index = 0;
		if (params != null && params.size() > 0) {
			for (Map.Entry<String, String> entry : params.entrySet()) {
				if (index != 0) {
					tokenUri.append("&");
				}
				tokenUri.append(entry.getKey());
				tokenUri.append("=");

				if (sendGet.equals(requestMethod)) {
					tokenUri.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
				} else {
					tokenUri.append(entry.getValue());
				}
				index++;
			}
		}
		// get 拼接地址
		if (sendGet.equals(requestMethod)) {
			url += "?" + tokenUri.toString();
		}
		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		con.setConnectTimeout(10000);// 10s内连不上就断开
		con.setRequestMethod(requestMethod);
		con.setDoInput(true);
		con.setDoOutput(true);
		con.setUseCaches(false);
		con.setRequestProperty("accept", "*/*");
		con.setRequestProperty("connection", "Keep-Alive");
		con.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
		con.setRequestProperty("Accept-Language", "UTF-8");
		con.setAllowUserInteraction(true);

		con.connect();
		// post 发送数据
		if (sendPost.equals(requestMethod)) {
			OutputStreamWriter outputStreamWriter = new OutputStreamWriter(con.getOutputStream());
			outputStreamWriter.write(tokenUri.toString());
			outputStreamWriter.flush();
			outputStreamWriter.close();
		}

		BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
		String line;
		while ((line = in.readLine()) != null) {
			response.append(line);
		}
		in.close();

		return response.toString();
	}
}
