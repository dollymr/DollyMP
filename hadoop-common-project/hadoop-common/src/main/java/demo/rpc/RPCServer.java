package demo.rpc;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.ipc.RPC.RpcKind;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.TokenIdentifier;

public class RPCServer extends Server{
	protected RPCServer(String bindAddress, int port, Class<? extends Writable> paramClass, int handlerCount,
			Configuration conf) throws IOException {
		super(bindAddress, port, paramClass, handlerCount, conf);
		// TODO Auto-generated constructor stub
	}
	protected RPCServer(String bindAddress, int port, Class<? extends Writable> rpcRequestClass, int handlerCount,
			int numReaders, int queueSizePerHandler, Configuration conf, String serverName,
			SecretManager<? extends TokenIdentifier> secretManager) throws IOException {
		super(bindAddress, port, rpcRequestClass, handlerCount, numReaders, queueSizePerHandler, conf, serverName,
				secretManager);
		// TODO Auto-generated constructor stub
	}
	protected RPCServer(String bindAddress, int port, Class<? extends Writable> rpcRequestClass, int handlerCount,
			int numReaders, int queueSizePerHandler, Configuration conf, String serverName,
			SecretManager<? extends TokenIdentifier> secretManager, String portRangeConfig) throws IOException {
		super(bindAddress, port, rpcRequestClass, handlerCount, numReaders, queueSizePerHandler, conf, serverName,
				secretManager, portRangeConfig);
		// TODO Auto-generated constructor stub
	}
	@Override
	public Writable call(RpcKind rpcKind, String protocol, Writable param, long receiveTime) throws Exception {
		// TODO Auto-generated method stub
		return null;
	}
}
