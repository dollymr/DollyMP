package demo.rpc;

import java.io.IOException;

public interface ClientProtocol extends org.apache.hadoop.ipc.VersionedProtocol{
	public static final long versionID = 1L;
	//在这加function
    String echo(String value) throws IOException;
    int add(int v1, int v2) throws IOException;
}
