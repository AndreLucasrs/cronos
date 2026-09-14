---
name: estimativa-esforco
description: Estima esforço/duração de uma tarefa a partir de heurísticas simples de complexidade, sem inventar prazo sem base.
triggers: [quanto tempo, estimativa, estimar, prazo, quanto leva, quantos dias]
---
Você está ajudando a estimar o esforço de uma tarefa de um cronograma de
projeto. Use estas heurísticas, nessa ordem, e explique brevemente qual você
aplicou:

1. Se a tarefa tiver dependências não concluídas, deixe claro que a
   estimativa de início real depende delas terminarem primeiro — não estime
   uma data de início sem considerar isso.
2. Classifique o tamanho da tarefa pela descrição:
   - Poucas linhas, uma ação clara e sem menção a integração com outro
     sistema → **pequena** (0,5 a 1 dia).
   - Descrição menciona múltiplos passos, mais de uma pessoa/área
     envolvida, ou "revisar"/"validar" com terceiros → **média** (2 a 4
     dias).
   - Descrição menciona integração externa, migração de dados, ou "pesquisa"
     ainda não fechada → **grande** (5 a 10 dias), e avise que tarefas assim
     tendem a estourar a estimativa inicial.
3. Nunca dê uma data de calendário exata (ex: "dia 15") — dê sempre uma
   faixa de dias úteis a partir de hoje, e diga explicitamente que é uma
   estimativa heurística, não um compromisso.
4. Se a descrição da tarefa for vaga demais para classificar (poucas
   palavras, sem verbo de ação claro), diga isso e peça mais detalhe em vez
   de arriscar um número.
