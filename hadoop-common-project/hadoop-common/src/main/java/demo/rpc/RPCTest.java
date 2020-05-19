package demo.rpc;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;

public class RPCTest {
	public static void main(String argv[]) throws HadoopIllegalArgumentException, IOException{
		Configuration server_conf = new Configuration();
		Configuration client_conf = new Configuration();
		String ADDRESS = "127.0.0.1";
		Server server = new RPC.Builder(server_conf).setProtocol(ClientProtocol.class)
			       .setInstance(new ClientProtocolImpl())
			       .setBindAddress(ADDRESS)
			       .setPort(0)
			       .setNumHandlers(5).build();
		server.start();
		InetSocketAddress addr = server.getListenerAddress();
		ClientProtocol proxy = (ClientProtocol)RPC.getProxy(
	              ClientProtocol.class, ClientProtocol.versionID, addr, client_conf);
	    int result = proxy.add(5, 6);
	    String echoResult = proxy.echo("result");
	    System.out.println(result);
	    System.out.println(echoResult);
	}
}
