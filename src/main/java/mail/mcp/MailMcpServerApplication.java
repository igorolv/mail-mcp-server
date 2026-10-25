package mail.mcp;

import mail.mcp.spike.EwsSpikeCommand;
import mail.mcp.spike.ImapSpikeCommand;
import mail.mcp.spike.GmailOAuthCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/**
 * Spring Boot entry point of the stdio MCP server. Empty by design.
 *
 * <p>The user-run {@code ews-spike}, {@code imap-spike} and {@code gmail-oauth} modes dispatch
 * before Spring starts, so they never open the stdio transport or wait for JSON-RPC on stdin.
 */
@SpringBootApplication
public class MailMcpServerApplication {

    static void main(String[] args) {
        if (args.length > 0 && EwsSpikeCommand.MODE.equals(args[0])) {
            System.exit(EwsSpikeCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && ImapSpikeCommand.MODE.equals(args[0])) {
            System.exit(ImapSpikeCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && GmailOAuthCommand.MODE.equals(args[0])) {
            System.exit(GmailOAuthCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(MailMcpServerApplication.class, args);
    }
}
