package mail.mcp;

import mail.mcp.cli.EwsSpikeCommand;
import mail.mcp.cli.ImapSpikeCommand;
import mail.mcp.cli.GmailOAuthCommand;
import mail.mcp.cli.MailSyncCommand;
import mail.mcp.cli.MailReprocessCommand;
import mail.mcp.cli.MailReindexCommand;
import mail.mcp.mime.MimeReader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/**
 * Spring Boot entry point of the stdio MCP server. Empty by design.
 *
 * <p>The user-run {@code ews-spike}, {@code imap-spike}, {@code gmail-oauth}, {@code mail-sync}
 * and offline {@code mail-reprocess}, {@code mail-reindex} modes dispatch
 * before Spring starts, so they never open the stdio transport or wait for JSON-RPC on stdin.
 */
@SpringBootApplication
public class MailMcpServerApplication {

    static void main(String[] args) {
        MimeReader.configureAngus();
        if (args.length > 0 && EwsSpikeCommand.MODE.equals(args[0])) {
            System.exit(EwsSpikeCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && ImapSpikeCommand.MODE.equals(args[0])) {
            System.exit(ImapSpikeCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && GmailOAuthCommand.MODE.equals(args[0])) {
            System.exit(GmailOAuthCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && MailSyncCommand.MODE.equals(args[0])) {
            System.exit(MailSyncCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && MailReprocessCommand.MODE.equals(args[0])) {
            System.exit(MailReprocessCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && MailReindexCommand.MODE.equals(args[0])) {
            System.exit(MailReindexCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(MailMcpServerApplication.class, args);
    }
}
