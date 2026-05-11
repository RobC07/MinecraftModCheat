import { EmbedBuilder, SlashCommandBuilder } from "discord.js";
import { getPrice, getLastUpdated } from "../lib/bazaar.js";
import type { Command } from "../lib/types.js";

function formatCoins(n: number): string {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(2)}B`;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(2)}K`;
  return n.toFixed(2);
}

const command: Command = {
  data: new SlashCommandBuilder()
    .setName("price")
    .setDescription("Show bazaar buy/sell/volume for a shard")
    .addStringOption((opt) =>
      opt
        .setName("shard")
        .setDescription("Bazaar product ID (e.g. ENCHANTED_DIAMOND)")
        .setRequired(true),
    ),
  async execute(interaction) {
    const rawId = interaction.options.getString("shard", true);
    const productId = rawId.trim().toUpperCase();

    await interaction.deferReply();
    const price = await getPrice(productId);

    if (!price) {
      await interaction.editReply(`No bazaar entry for \`${productId}\`.`);
      return;
    }

    const ageMin = Math.floor((Date.now() - getLastUpdated().getTime()) / 60_000);
    const embed = new EmbedBuilder()
      .setTitle(productId)
      .addFields(
        { name: "Buy", value: formatCoins(price.buyPrice), inline: true },
        { name: "Sell", value: formatCoins(price.sellPrice), inline: true },
        {
          name: "Sell volume",
          value: price.sellVolume.toLocaleString("en-US"),
          inline: true,
        },
        {
          name: "Weekly moving sell volume",
          value: price.weeklyMovingSellVolume.toLocaleString("en-US"),
          inline: true,
        },
      )
      .setFooter({ text: `Bazaar cached ${ageMin}m ago` });

    await interaction.editReply({ embeds: [embed] });
  },
};

export default command;
