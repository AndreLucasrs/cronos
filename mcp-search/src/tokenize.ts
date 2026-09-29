const STOPWORDS = new Set([
  // Portuguese function words that would otherwise dilute a lexical match.
  "a", "o", "os", "as", "de", "da", "do", "das", "dos", "em", "na", "no", "nas", "nos",
  "para", "por", "com", "que", "um", "uma", "uns", "umas", "ou", "e", "se", "ja", "já",
  "esse", "essa", "isso", "este", "esta", "isto", "qual", "quais", "quando", "onde",
  "tem", "ha", "há", "sobre", "algum", "alguma", "alguns", "algumas",
  // Domain/skill scaffolding words — present in most trigger phrases, would
  // otherwise match nearly any task and defeat the point of a lexical search.
  "tarefa", "tarefas", "buscar", "encontrar", "existe",
]);

/**
 * Splits a free-form question into significant search terms: this is what
 * lets "existe alguma tarefa sobre OAuth2?" still find a task titled
 * "Migrar autenticação para OAuth2" under a literal ILIKE match, without
 * requiring the caller to already know it needs to search for just
 * "OAuth2" — still lexical (no embeddings), just tokenized instead of
 * matched as one long literal string.
 */
export function tokenize(query: string): string[] {
  const words = query
    .toLowerCase()
    .match(/[\p{L}\p{N}]{3,}/gu) ?? [];
  const significant = words.filter((word) => !STOPWORDS.has(word));
  return [...new Set(significant.length > 0 ? significant : words)];
}
