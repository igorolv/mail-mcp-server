package mail.mcp;

import mail.mcp.spike.EwsSpikeCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/**
 * Spring Boot entry point of the stdio MCP server. Empty by design.
 *
 * <p>The one exception is the EWS spike: {@code java -jar mail-mcp-server.jar ews-spike <connection>}
 * runs {@link EwsSpikeCommand} instead of the server. It is dispatched before Spring starts, so the
 * spike never opens the stdio transport and never waits for JSON-RPC on stdin.
 */
@SpringBootApplication
public class MailMcpServerApplication {

    static void main(String[] args) {
        if (args.length > 0 && EwsSpikeCommand.MODE.equals(args[0])) {
            System.exit(EwsSpikeCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(MailMcpServerApplication.class, args);
    }
}
