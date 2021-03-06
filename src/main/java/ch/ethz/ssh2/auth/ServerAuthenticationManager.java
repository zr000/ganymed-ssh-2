
package ch.ethz.ssh2.auth;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import ch.ethz.ssh2.AuthenticationResult;
import ch.ethz.ssh2.PacketTypeException;
import ch.ethz.ssh2.ServerAuthenticationCallback;
import ch.ethz.ssh2.channel.ChannelManager;
import ch.ethz.ssh2.packets.PacketServiceAccept;
import ch.ethz.ssh2.packets.PacketServiceRequest;
import ch.ethz.ssh2.packets.PacketUserauthBanner;
import ch.ethz.ssh2.packets.PacketUserauthFailure;
import ch.ethz.ssh2.packets.PacketUserauthSuccess;
import ch.ethz.ssh2.packets.Packets;
import ch.ethz.ssh2.packets.TypesReader;
import ch.ethz.ssh2.server.ServerConnectionState;
import ch.ethz.ssh2.transport.MessageHandler;

public class ServerAuthenticationManager implements MessageHandler {
    private final ServerConnectionState state;

    public ServerAuthenticationManager(ServerConnectionState state) {
        this.state = state;
        state.tm.registerMessageHandler(this, 0, 255);
    }

    private void sendresult(AuthenticationResult result) throws IOException {
        if(AuthenticationResult.SUCCESS == result) {
            PacketUserauthSuccess pus = new PacketUserauthSuccess();
            state.tm.sendAsynchronousMessage(pus.getPayload());

            state.tm.removeMessageHandler(this);
            state.tm.registerMessageHandler(this, 50, 79);

            state.cm = new ChannelManager(state);

            state.flag_auth_completed = true;

        }
        else {
            Set<String> remaining_methods = new HashSet<String>();

            if(state.cb_auth != null) {
                remaining_methods.addAll(Arrays.asList(
                        state.cb_auth.getRemainingAuthMethods(state.conn)));
            }
            PacketUserauthFailure puf = new PacketUserauthFailure(remaining_methods,
                    AuthenticationResult.PARTIAL_SUCCESS == result);
            state.tm.sendAsynchronousMessage(puf.getPayload());
        }
    }

    @Override
    public void handleFailure(final IOException failure) {
        //
    }

    @Override
    public void handleMessage(byte[] msg) throws IOException {
        /* Ignore all authentication messages after successful auth */

        if(state.flag_auth_completed) {
            return;
        }

        if(!state.flag_auth_serviceRequested) {
			/* Must be PacketServiceRequest */

            PacketServiceRequest psr = new PacketServiceRequest(msg);

            if(!"ssh-userauth".equals(psr.getServiceName())) {
                throw new IOException("SSH protocol error, expected ssh-userauth service request");
            }

            PacketServiceAccept psa = new PacketServiceAccept("ssh-userauth");
            state.tm.sendAsynchronousMessage(psa.getPayload());

            String banner = state.cb_auth.initAuthentication(state.conn);

            if(banner != null) {
                PacketUserauthBanner pub = new PacketUserauthBanner(banner);
                state.tm.sendAsynchronousMessage(pub.getPayload());
            }

            state.flag_auth_serviceRequested = true;

            return;
        }

        ServerAuthenticationCallback cb = state.cb_auth;

        TypesReader tr = new TypesReader(msg);
        int packet_type = tr.readByte();

        if(packet_type == Packets.SSH_MSG_USERAUTH_REQUEST) {
            String username = tr.readString("UTF-8");
            String service = tr.readString();
            String method = tr.readString();

            if(!"ssh-connection".equals(service)) {
                sendresult(AuthenticationResult.FAILURE);
                return;
            }

            if("none".equals(method)) {
                if(cb != null) {
                    sendresult(cb.authenticateWithNone(state.conn, username));
                    return;
                }
            }

            if("password".equals(method)) {
                boolean flag_change_pass = tr.readBoolean();

                if(flag_change_pass) {
                    sendresult(AuthenticationResult.FAILURE);
                    return;
                }

                String password = tr.readString("UTF-8");

                if(cb != null) {
                    sendresult(cb.authenticateWithPassword(state.conn, username, password));
                    return;
                }
            }

            sendresult(AuthenticationResult.FAILURE);
            return;
        }
        throw new PacketTypeException(packet_type);
    }
}
