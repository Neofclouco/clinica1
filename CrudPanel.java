# Medições e planos reais

Execução em 26/10/2026; MySQL 8.0.46-0ubuntu0.24.04.3. Carga original, conexão TCP local, sem ampliação artificial.

Cinco aquecimentos e 30 repetições por cenário; tempos incluem cliente, transporte e leitura de todas as linhas. Ordem antes/depois fixa, sem desenho estatístico pareado. Variações submilissegundo não demonstram ganho em produção.

## Consulta 1: idx_paciente_busca

```sql
SELECT id_paciente,nome,data_nascimento,telefone FROM paciente WHERE data_nascimento BETWEEN '1985-01-01' AND '1995-12-31' AND id_convenio IN (1,2,3,4) AND telefone IS NULL;
```

Índice: `data_nascimento, id_convenio, telefone`.

### Antes

Mediana: 0.235294 ms; mínimo: 0.209922; máximo: 0.919264; linhas retornadas: 0.

EXPLAIN (colunas: id, select_type, table, partitions, type, possible_keys, key, key_len, ref, rows, filtered, Extra):

```text
1	SIMPLE	paciente	None	ALL	idx_paciente_convenio	None	None	None	40	2.5	Using where
```

EXPLAIN ANALYZE:

```text
-> Filter: ((paciente.data_nascimento between '1985-01-01' and '1995-12-31') and (paciente.id_convenio in (1,2,3,4)) and (paciente.telefone is null))  (cost=4.25 rows=1) (actual time=0.0357..0.0357 rows=0 loops=1)
    -> Table scan on paciente  (cost=4.25 rows=40) (actual time=0.00674..0.0215 rows=40 loops=1)

```

### Depois

Mediana: 0.161809 ms; mínimo: 0.128410; máximo: 0.667590; linhas retornadas: 0.

EXPLAIN (colunas: id, select_type, table, partitions, type, possible_keys, key, key_len, ref, rows, filtered, Extra):

```text
1	SIMPLE	paciente	None	range	idx_paciente_convenio,idx_paciente_busca	idx_paciente_busca	91	None	13	4.0	Using index condition
```

EXPLAIN ANALYZE:

```text
-> Index range scan on paciente using idx_paciente_busca over ('1985-01-01' <= data_nascimento <= '1995-12-31' AND 1 <= id_convenio <= 4 AND telefone = NULL), with index condition: ((paciente.data_nascimento between '1985-01-01' and '1995-12-31') and (paciente.id_convenio in (1,2,3,4)) and (paciente.telefone is null))  (cost=6.11 rows=13) (actual time=0.0102..0.0102 rows=0 loops=1)

```

Leitura e justificativa: O acesso a paciente passou de ALL (40 linhas estimadas) a range (13), com Using index condition. A data restringe um intervalo; convênio e telefone podem filtrar no índice, mas não se deve afirmar busca igualmente seletiva por todas as colunas. A mediana caiu nesta rodada, sem extrapolação para produção.

Conclusão: mediana menor nesta execução.

## Consulta 2: idx_sessao_situacao

```sql
SELECT p.nome,COUNT(s.numero_sessao) FROM paciente p JOIN tratamento t ON t.id_paciente=p.id_paciente JOIN sessao s ON s.id_tratamento=t.id_tratamento WHERE s.situacao='REALIZADA' GROUP BY p.id_paciente,p.nome ORDER BY COUNT(s.numero_sessao) DESC;
```

Índice: `situacao, id_tratamento`.

### Antes

Mediana: 0.584083 ms; mínimo: 0.459592; máximo: 0.828038; linhas retornadas: 40.

EXPLAIN (colunas: id, select_type, table, partitions, type, possible_keys, key, key_len, ref, rows, filtered, Extra):

```text
1	SIMPLE	p	None	ALL	PRIMARY	None	None	None	40	100.0	Using temporary; Using filesort
1	SIMPLE	t	None	ref	PRIMARY,uq_tratamento_paciente_especialidade_aberto,idx_tratamento_paciente	uq_tratamento_paciente_especialidade_aberto	4	clinica_fisioterapia.p.id_paciente	1	100.0	Using index
1	SIMPLE	s	None	ref	PRIMARY	PRIMARY	4	clinica_fisioterapia.t.id_tratamento	5	25.0	Using where
```

EXPLAIN ANALYZE:

```text
-> Sort: `COUNT(s.numero_sessao)` DESC  (actual time=0.262..0.263 rows=40 loops=1)
    -> Table scan on <temporary>  (actual time=0.251..0.254 rows=40 loops=1)
        -> Aggregate using temporary table  (actual time=0.25..0.25 rows=40 loops=1)
            -> Nested loop inner join  (cost=52 rows=59.3) (actual time=0.016..0.179 rows=182 loops=1)
                -> Nested loop inner join  (cost=18.2 rows=40) (actual time=0.011..0.057 rows=40 loops=1)
                    -> Table scan on p  (cost=4.25 rows=40) (actual time=0.00633..0.014 rows=40 loops=1)
                    -> Covering index lookup on t using uq_tratamento_paciente_especialidade_aberto (id_paciente=p.id_paciente)  (cost=0.253 rows=1) (actual time=706e-6..927e-6 rows=1 loops=40)
                -> Filter: (s.situacao = 'REALIZADA')  (cost=0.255 rows=1.48) (actual time=0.00145..0.00267 rows=4.55 loops=40)
                    -> Index lookup on s using PRIMARY (id_tratamento=t.id_tratamento)  (cost=0.255 rows=5.93) (actual time=0.00125..0.00192 rows=5.92 loops=40)

```

### Depois

Mediana: 0.614298 ms; mínimo: 0.459802; máximo: 2.559727; linhas retornadas: 40.

EXPLAIN (colunas: id, select_type, table, partitions, type, possible_keys, key, key_len, ref, rows, filtered, Extra):

```text
1	SIMPLE	p	None	ALL	PRIMARY	None	None	None	40	100.0	Using temporary; Using filesort
1	SIMPLE	t	None	ref	PRIMARY,uq_tratamento_paciente_especialidade_aberto,idx_tratamento_paciente	uq_tratamento_paciente_especialidade_aberto	4	clinica_fisioterapia.p.id_paciente	1	100.0	Using index
1	SIMPLE	s	None	ref	PRIMARY,idx_sessao_situacao	idx_sessao_situacao	5	const,clinica_fisioterapia.t.id_tratamento	3	100.0	Using where; Using index
```

EXPLAIN ANALYZE:

```text
-> Sort: `COUNT(s.numero_sessao)` DESC  (actual time=0.283..0.284 rows=40 loops=1)
    -> Table scan on <temporary>  (actual time=0.271..0.275 rows=40 loops=1)
        -> Aggregate using temporary table  (actual time=0.271..0.271 rows=40 loops=1)
            -> Nested loop inner join  (cost=40.8 rows=125) (actual time=0.0162..0.198 rows=182 loops=1)
                -> Nested loop inner join  (cost=18.2 rows=40) (actual time=0.0116..0.0856 rows=40 loops=1)
                    -> Table scan on p  (cost=4.25 rows=40) (actual time=0.00671..0.0149 rows=40 loops=1)
                    -> Covering index lookup on t using uq_tratamento_paciente_especialidade_aberto (id_paciente=p.id_paciente)  (cost=0.253 rows=1) (actual time=772e-6..0.00101 rows=1 loops=40)
                -> Filter: (s.situacao = 'REALIZADA')  (cost=0.259 rows=3.12) (actual time=0.00133..0.00244 rows=4.55 loops=40)
                    -> Covering index lookup on s using idx_sessao_situacao (situacao='REALIZADA', id_tratamento=t.id_tratamento)  (cost=0.259 rows=3.12) (actual time=0.00114..0.00179 rows=4.55 loops=40)

```

Leitura e justificativa: A junção de sessão continuou ref, substituindo PRIMARY por idx_sessao_situacao; o índice cobre a consulta e usa situação e tratamento. A mediana aumentou nesta rodada. O plano não era uma varredura total de sessão antes, ao contrário do relatório antigo. Baixo volume e ruído dominam diferenças pequenas.

Conclusão: sem ganho de tempo nesta execução.

## Consulta 3: idx_avaliacao_dor

```sql
SELECT p.id_paciente,p.nome,AVG(a.nivel_dor) FROM paciente p JOIN avaliacao_fisica a ON a.id_paciente=p.id_paciente WHERE EXISTS (SELECT 1 FROM tratamento t JOIN sessao s ON s.id_tratamento=t.id_tratamento WHERE t.id_paciente=p.id_paciente AND s.situacao='REALIZADA') GROUP BY p.id_paciente,p.nome HAVING AVG(a.nivel_dor)>=6;
```

Índice: `id_paciente, nivel_dor`.

### Antes

Mediana: 0.660720 ms; mínimo: 0.592949; máximo: 1.255532; linhas retornadas: 14.

EXPLAIN (colunas: id, select_type, table, partitions, type, possible_keys, key, key_len, ref, rows, filtered, Extra):

```text
1	SIMPLE	<subquery2>	None	ALL	None	None	None	None	None	100.0	Using temporary
1	SIMPLE	p	None	eq_ref	PRIMARY	PRIMARY	4	<subquery2>.id_paciente	1	100.0	None
1	SIMPLE	a	None	ref	uq_avaliacao_paciente_data,idx_evidence_fk	uq_avaliacao_paciente_data	4	<subquery2>.id_paciente	2	100.0	None
2	MATERIALIZED	t	None	index	PRIMARY,uq_tratamento_paciente_especialidade_aberto,idx_tratamento_paciente	idx_tratamento_paciente	4	None	40	100.0	Using index
2	MATERIALIZED	s	None	ref	PRIMARY,idx_sessao_situacao	idx_sessao_situacao	5	const,clinica_fisioterapia.t.id_tratamento	3	100.0	Using where; Using index
```

EXPLAIN ANALYZE:

```text
-> Filter: (??? >= 6)  (actual time=0.643..0.651 rows=14 loops=1)
    -> Table scan on <temporary>  (actual time=0.641..0.644 rows=37 loops=1)
        -> Aggregate using temporary table  (actual time=0.641..0.641 rows=37 loops=1)
            -> Nested loop inner join  (cost=91.4 rows=347) (actual time=0.247..0.505 rows=103 loops=1)
                -> Nested loop inner join  (cost=56 rows=125) (actual time=0.242..0.299 rows=40 loops=1)
                    -> Table scan on <subquery2>  (cost=39.3..43.3 rows=125) (actual time=0.238..0.242 rows=40 loops=1)
                        -> Materialize with deduplication  (cost=39.2..39.2 rows=125) (actual time=0.238..0.238 rows=40 loops=1)
                            -> Nested loop inner join  (cost=26.8 rows=125) (actual time=0.0465..0.215 rows=182 loops=1)
                                -> Covering index scan on t using idx_tratamento_paciente  (cost=4.25 rows=40) (actual time=0.0395..0.0477 rows=40 loops=1)
                                -> Filter: (s.situacao = 'REALIZADA')  (cost=0.259 rows=3.12) (actual time=0.00164..0.00383 rows=4.55 loops=40)
                                    -> Covering index lookup on s using idx_sessao_situacao (situacao='REALIZADA', id_tratamento=t.id_tratamento)  (cost=0.259 rows=3.12) (actual time=0.00138..0.0031 rows=4.55 loops=40)
                    -> Single-row index lookup on p using PRIMARY (id_paciente=`<subquery2>`.id_paciente)  (cost=0.35 rows=1) (actual time=0.00122..0.00125 rows=1 loops=40)
                -> Index lookup on a using uq_avaliacao_paciente_data (id_paciente=`<subquery2>`.id_paciente)  (cost=0.974 rows=2.78) (actual time=0.00347..0.00489 rows=2.58 loops=40)

```

### Depois

Mediana: 0.745641 ms; mínimo: 0.552860; máximo: 2.719333; linhas retornadas: 14.

EXPLAIN (colunas: id, select_type, table, partitions, type, possible_keys, key, key_len, ref, rows, filtered, Extra):

```text
1	SIMPLE	<subquery2>	None	ALL	None	None	None	None	None	100.0	Using temporary
1	SIMPLE	p	None	eq_ref	PRIMARY	PRIMARY	4	<subquery2>.id_paciente	1	100.0	None
1	SIMPLE	a	None	ref	uq_avaliacao_paciente_data,idx_avaliacao_dor	idx_avaliacao_dor	4	<subquery2>.id_paciente	2	100.0	Using index
2	MATERIALIZED	t	None	index	PRIMARY,uq_tratamento_paciente_especialidade_aberto,idx_tratamento_paciente	idx_tratamento_paciente	4	None	40	100.0	Using index
2	MATERIALIZED	s	None	ref	PRIMARY,idx_sessao_situacao	idx_sessao_situacao	5	const,clinica_fisioterapia.t.id_tratamento	3	100.0	Using where; Using index
```

EXPLAIN ANALYZE:

```text
-> Filter: (??? >= 6)  (actual time=0.307..0.315 rows=14 loops=1)
    -> Table scan on <temporary>  (actual time=0.305..0.309 rows=37 loops=1)
        -> Aggregate using temporary table  (actual time=0.305..0.305 rows=37 loops=1)
            -> Nested loop inner join  (cost=91 rows=347) (actual time=0.154..0.247 rows=103 loops=1)
                -> Nested loop inner join  (cost=56 rows=125) (actual time=0.151..0.184 rows=40 loops=1)
                    -> Table scan on <subquery2>  (cost=39.3..43.3 rows=125) (actual time=0.148..0.152 rows=40 loops=1)
                        -> Materialize with deduplication  (cost=39.2..39.2 rows=125) (actual time=0.147..0.147 rows=40 loops=1)
                            -> Nested loop inner join  (cost=26.8 rows=125) (actual time=0.0105..0.126 rows=182 loops=1)
                                -> Covering index scan on t using idx_tratamento_paciente  (cost=4.25 rows=40) (actual time=0.00498..0.0126 rows=40 loops=1)
                                -> Filter: (s.situacao = 'REALIZADA')  (cost=0.259 rows=3.12) (actual time=0.00139..0.0025 rows=4.55 loops=40)
                                    -> Covering index lookup on s using idx_sessao_situacao (situacao='REALIZADA', id_tratamento=t.id_tratamento)  (cost=0.259 rows=3.12) (actual time=0.00118..0.00186 rows=4.55 loops=40)
                    -> Single-row index lookup on p using PRIMARY (id_paciente=`<subquery2>`.id_paciente)  (cost=0.35 rows=1) (actual time=627e-6..651e-6 rows=1 loops=40)
                -> Covering index lookup on a using idx_avaliacao_dor (id_paciente=`<subquery2>`.id_paciente)  (cost=0.529 rows=2.78) (actual time=937e-6..0.00132 rows=2.58 loops=40)

```

Leitura e justificativa: Avaliação passou a usar idx_avaliacao_dor com Using index: cobertura por paciente e nível de dor. A mediana aumentou, apesar do plano coberto. O helper de FK temporário foi criado somente antes da remoção do índice e removido depois; a alternativa realmente escolhida antes foi uq_avaliacao_paciente_data. Os outros índices permaneceram ativos.

Conclusão: sem ganho de tempo nesta execução.

