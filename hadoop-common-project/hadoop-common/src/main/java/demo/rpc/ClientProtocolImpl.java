package demo.rpc;

import java.io.IOException;

import org.apache.hadoop.ipc.ProtocolSignature;

public class ClientProtocolImpl implements ClientProtocol{
	//Get the user-defined protocol version.
	public long getProtocolVersion(String protocol, long clientVersion) {
        return ClientProtocol.versionID;
    }
	//Get the Protocol Signature.
	public ProtocolSignature getProtocolSignature(String protocol, long clientVersion, int hashcode) {
        return new ProtocolSignature(ClientProtocol.versionID, null);
    }
	//These are the user-defined functions.
	public String echo(String value) throws IOException {
		System.out.println("echo call");
        return value;
    }
	public int add(int v1, int v2) throws IOException {
		System.out.println("add call");
        return v1 + v2;
	}
	
}
