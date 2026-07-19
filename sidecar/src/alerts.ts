// Push proactive-event comments to an external channel so the player sees them while
// tabbed out. Off unless env is set: WOC_ALERT_WEBHOOK (Discord-style {content} webhook)
// and/or WOC_TELEGRAM_BOT_TOKEN + WOC_TELEGRAM_CHAT_ID. Best-effort, never throws.

export function alertsEnabled(): boolean {
  return !!(
    process.env.WOC_ALERT_WEBHOOK ||
    (process.env.WOC_TELEGRAM_BOT_TOKEN && process.env.WOC_TELEGRAM_CHAT_ID)
  );
}

export async function sendAlert(text: string): Promise<void> {
  const body = text.trim();
  if (!body) return;
  const tasks: Promise<unknown>[] = [];

  const webhook = process.env.WOC_ALERT_WEBHOOK;
  if (webhook) {
    tasks.push(
      fetch(webhook, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: body }),
      }).catch(() => {}),
    );
  }

  const token = process.env.WOC_TELEGRAM_BOT_TOKEN;
  const chat = process.env.WOC_TELEGRAM_CHAT_ID;
  if (token && chat) {
    tasks.push(
      fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ chat_id: chat, text: body }),
      }).catch(() => {}),
    );
  }

  await Promise.allSettled(tasks);
}
