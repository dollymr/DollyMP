package demo.rpc;

import javax.net.SocketFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.ipc.Client;

public class RPCClient extends Client{
	public RPCClient(Class<? extends Writable> valueClass, Configuration conf) {
		super(valueClass, conf);
		// TODO Auto-generated constructor stub
	}
	public RPCClient(Class<? extends Writable> valueClass, Configuration conf, SocketFactory factory) {
		super(valueClass, conf, factory);
		// TODO Auto-generated constructor stub
	}
}
