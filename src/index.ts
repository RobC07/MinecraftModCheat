import "dotenv/config";
import { readdir } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import {
  Client,
  Events,
  GatewayIntentBits,
  REST,
  Routes,
  Collection,
} from "discord.js";
import type { Command } from "./lib/types.js";

const { DISCORD_TOKEN, GUILD_ID, BOT_CLIENT_ID } = process.env;
if (!DISCORD_TOKEN || !GUILD_ID || !BOT_CLIENT_ID) {
  throw new Error("Missing DISCORD_TOKEN, GUILD_ID, or BOT_CLIENT_ID in env");
}

const commands = new Collection<string, Command>();

async function loadCommands() {
  const here = dirname(fileURLToPath(import.meta.url));
  const dir = join(here, "commands");
  const files = await readdir(dir);
  for (const file of files) {
    if (!/\.(ts|js)$/.test(file)) continue;
    const mod = await import(pathToFileURL(join(dir, file)).href);
    const cmd = mod.default as Command | undefined;
    if (!cmd?.data?.name) {
      console.warn(`Skipping ${file}: no default export with .data`);
      continue;
    }
    commands.set(cmd.data.name, cmd);
  }
}

async function registerCommands() {
  const body = commands.map((c) => c.data.toJSON());
  const rest = new REST({ version: "10" }).setToken(DISCORD_TOKEN!);
  await rest.put(Routes.applicationGuildCommands(BOT_CLIENT_ID!, GUILD_ID!), {
    body,
  });
  console.log(`Registered ${body.length} guild command(s) to ${GUILD_ID}`);
}

const client = new Client({ intents: [GatewayIntentBits.Guilds] });

client.once(Events.ClientReady, (c) => {
  console.log(`Ready as ${c.user.tag}`);
});

client.on(Events.InteractionCreate, async (interaction) => {
  if (!interaction.isChatInputCommand()) return;
  const cmd = commands.get(interaction.commandName);
  if (!cmd) return;
  try {
    await cmd.execute(interaction);
  } catch (err) {
    console.error(`Command ${interaction.commandName} failed:`, err);
    const reply = { content: "Command failed. Check logs.", ephemeral: true };
    if (interaction.deferred || interaction.replied) {
      await interaction.followUp(reply).catch(() => {});
    } else {
      await interaction.reply(reply).catch(() => {});
    }
  }
});

function shutdown(signal: string) {
  console.log(`Received ${signal}, shutting down...`);
  client.destroy().finally(() => process.exit(0));
}
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

await loadCommands();
await registerCommands();
await client.login(DISCORD_TOKEN);
