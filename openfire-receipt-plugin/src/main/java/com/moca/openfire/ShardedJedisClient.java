package com.moca.openfire;

import java.util.ArrayList;
import java.util.List;

import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisShardInfo;
import redis.clients.jedis.ShardedJedis;
import redis.clients.jedis.ShardedJedisPool;

public class ShardedJedisClient {
    /**
     * 切片客户端链接
     */
	public ShardedJedis shardedJedis;

    /**
     * 切片链接池
     */
    private ShardedJedisPool shardedJedisPool;
    
	//内网IP 192.168.0.244
	private String ip = "127.0.0.1";
	private int port = 6379;
	
	private static ShardedJedisClient instance = null;
	
	public static synchronized ShardedJedisClient getInstance() {
		return new ShardedJedisClient();
	}
	
	private ShardedJedisClient() {
		initialShardedPool();
        shardedJedis = shardedJedisPool.getResource();
	}
	
	private void initialShardedPool() {
        // 池基本配置
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxIdle(200);
        config.setMaxWaitMillis(3000l);
        config.setTestOnBorrow(true);

        // slave链接
        List<JedisShardInfo> shards = new ArrayList<JedisShardInfo>();
        shards.add(new JedisShardInfo(ip, port, "master"));
        // 构造池
        shardedJedisPool = new ShardedJedisPool(config, shards);

    }

	public void destroy() {
        shardedJedisPool.close();
	}
	
	public static void main(String[] args) {
		ShardedJedisClient client = new ShardedJedisClient();
		List<String> list = client.shardedJedis.lrange("1229165", 0, -1);
		System.out.println(list.size());
		for (String key : list) {
			System.out.println(client.shardedJedis.get(key));
		}
//		Set<HostAndPort> jedisClusterNodes = new HashSet<HostAndPort>();
//		//Jedis Cluster will attempt to discover cluster nodes automatically
//		jedisClusterNodes.add(new HostAndPort("192.168.0.136", 6379));
//		JedisCluster jc = new JedisCluster(jedisClusterNodes);
//		String value = jc.get("13714617163");
//		System.out.print(value);
	}
}
