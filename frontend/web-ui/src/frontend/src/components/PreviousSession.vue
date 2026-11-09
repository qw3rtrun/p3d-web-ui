<script setup lang="ts">
/**
 * The mark a panel wears while it is showing values from a session that has ended - the visible
 * half of `useSessionScope` (`src/session.ts`), which holds the rule and the reasoning.
 *
 * Said in words, not in colour alone: Bootstrap's muted greys are the only thing the two themes
 * agree on, and a greyed table says "disabled" to most people rather than "from before". `what`
 * names the thing so one sentence serves every panel ("This listing is from…").
 *
 * It does not offer a refresh. The panel's own Read / Refresh button is already gated on the role
 * and on the link, and a VIEWER - who cannot refresh at all, since `POST /api-printer/**` is
 * OPERATOR/ADMIN - must not be shown a control that can only 403.
 */
defineProps<{
    /** Subject of the sentence, capitalised: "These settings", "This listing". */
    what: string
}>()
</script>

<template>
  <div class="alert alert-secondary py-2 d-flex align-items-start gap-2" role="status">
    <span class="badge bg-secondary flex-shrink-0">previous session</span>
    <span>
      {{ what }} came from before the machine reconnected, and nothing has confirmed it since. What
      came back on the same address may not be the same firmware, so read it again before trusting
      it.
    </span>
  </div>
</template>
