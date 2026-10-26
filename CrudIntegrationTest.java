"""Evidências B3/B4/B5/B6 em cópia DESCARTÁVEL, já restaurada com etapa01/02.
Não cria/recria o banco. Cria contas temporárias e remove/recria três índices.
Exige EVIDENCE_CONFIRM_DISPOSABLE=YES e credenciais administrativas externas.
Saída não inclui senhas; contas e dados temporários são limpos no finally.
"""
import os, json, time, statistics, secrets, threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import pymysql

assert os.environ.get('EVIDENCE_CONFIRM_DISPOSABLE')=='YES', 'Confirme ambiente descartável.'
cfg=dict(host=os.getenv('MYSQL_HOST','127.0.0.1'),port=int(os.getenv('MYSQL_PORT','3306')),
         user=os.environ['MYSQL_USER'],password=os.environ['MYSQL_PASSWORD'],
         database='clinica_fisioterapia',charset='utf8mb4',autocommit=True)
out=Path(os.environ.get('EVIDENCE_OUT','docs/evidencias'));out.mkdir(parents=True,exist_ok=True)
def connect(**kw):return pymysql.connect(**(cfg|kw))
def query(c,sql,args=None):
    with c.cursor() as s:s.execute(sql,args);return s.fetchall()
def scalar(c,sql,args=None):return query(c,sql,args)[0][0]
c=connect();result={'environment':{},'benchmarks':[],'profiles':[]}
users=[]
try:
    result['environment']={'version':scalar(c,'SELECT VERSION()'),'isolation':scalar(c,'SELECT @@transaction_isolation'),
        'sql_mode':scalar(c,'SELECT @@sql_mode'),'source_commit':'02e1469b74d535a7c52ebbe2152d7332c764de7a',
        'dataset':{t:scalar(c,'SELECT COUNT(*) FROM '+t) for t in ['paciente','profissional','tratamento','sessao','avaliacao_fisica','evolucao','aplicacao_exercicio']},
        'note':'Cópia da carga original, sem ampliação sintética. Valores de tempo não representam produção.'}
    specs=[
        ('paciente','idx_paciente_busca','data_nascimento, id_convenio, telefone',"SELECT id_paciente,nome,data_nascimento,telefone FROM paciente WHERE data_nascimento BETWEEN '1985-01-01' AND '1995-12-31' AND id_convenio IN (1,2,3,4) AND telefone IS NULL"),
        ('sessao','idx_sessao_situacao','situacao, id_tratamento',"SELECT p.nome,COUNT(s.numero_sessao) FROM paciente p JOIN tratamento t ON t.id_paciente=p.id_paciente JOIN sessao s ON s.id_tratamento=t.id_tratamento WHERE s.situacao='REALIZADA' GROUP BY p.id_paciente,p.nome ORDER BY COUNT(s.numero_sessao) DESC"),
        ('avaliacao_fisica','idx_avaliacao_dor','id_paciente, nivel_dor',"SELECT p.id_paciente,p.nome,AVG(a.nivel_dor) FROM paciente p JOIN avaliacao_fisica a ON a.id_paciente=p.id_paciente WHERE EXISTS (SELECT 1 FROM tratamento t JOIN sessao s ON s.id_tratamento=t.id_tratamento WHERE t.id_paciente=p.id_paciente AND s.situacao='REALIZADA') GROUP BY p.id_paciente,p.nome HAVING AVG(a.nivel_dor)>=6")]
    def measure(sql):
        for _ in range(5):query(c,sql)
        values=[]
        for _ in range(30):
            t=time.perf_counter_ns();rows=query(c,sql);values.append((time.perf_counter_ns()-t)/1e6)
        return {'median_ms':statistics.median(values),'min_ms':min(values),'max_ms':max(values),'samples_ms':values,'rows_returned':len(rows),'explain':query(c,'EXPLAIN '+sql),'explain_analyze':query(c,'EXPLAIN ANALYZE '+sql)}
    for table,index,columns,sql in specs:
        # Identificadores pertencem à lista fixa acima, nunca à entrada do usuário.
        # A FK de avaliação precisa de outro índice iniciado por id_paciente antes da remoção.
        helper=table=='avaliacao_fisica'
        if helper:query(c,'CREATE INDEX idx_evidence_fk ON avaliacao_fisica(id_paciente)')
        query(c,f'DROP INDEX {index} ON {table}')
        try:
            query(c,f'ANALYZE TABLE {table}');before=measure(sql)
        finally:query(c,f'CREATE INDEX {index} ON {table} ({columns})')
        if helper:query(c,'DROP INDEX idx_evidence_fk ON avaliacao_fisica')
        query(c,f'ANALYZE TABLE {table}');after=measure(sql)
        result['benchmarks'].append({'table':table,'index':index,'columns':columns,'sql':sql,'before':before,'after':after})

    # Dois logins/conexões reais e duas transações simultâneas sobre uma linha temporária.
    query(c,"INSERT INTO sala(codigo,capacidade) VALUES ('EVID-CONC',1)");sid=scalar(c,'SELECT LAST_INSERT_ID()')
    a=connect(autocommit=False);b=connect(autocommit=False)
    try:
        query(a,'SELECT capacidade FROM sala WHERE id_sala=%s FOR UPDATE',(sid,))
        query(b,'SET SESSION innodb_lock_wait_timeout=5')
        started=threading.Event()
        def contender():
            started.set();t=time.perf_counter();query(b,'UPDATE sala SET capacidade=capacidade+1 WHERE id_sala=%s',(sid,));b.commit();return time.perf_counter()-t
        with ThreadPoolExecutor(1) as pool:
            f=pool.submit(contender);started.wait(2)
            waits=0
            for _ in range(100):
                waits=scalar(c,'SELECT COUNT(*) FROM performance_schema.data_lock_waits')
                if waits:break
                time.sleep(.02)
            assert waits>0 and not f.done(),'Espera não observada'
            query(a,'UPDATE sala SET capacidade=capacidade+1 WHERE id_sala=%s',(sid,));a.commit();elapsed=f.result(timeout=8)
        final=scalar(c,'SELECT capacidade FROM sala WHERE id_sala=%s',(sid,));assert final==3
        result['concurrency']={'isolation':scalar(c,'SELECT @@transaction_isolation'),'lock_waits_observed':waits,'session_b_wait_seconds':elapsed,'initial_capacity':1,'final_capacity':final,'outcome':'B esperou o bloqueio de A; após COMMIT de A, B incrementou sem perda de atualização.'}
    finally:a.rollback();b.rollback();a.close();b.close();query(c,'DELETE FROM sala WHERE id_sala=%s',(sid,))

    for role in ['Administrador','Fisioterapeuta','Estagiario']:
        user='evid_'+secrets.token_hex(5);pw=secrets.token_urlsafe(24);users.append(user)
        query(c,'CREATE USER %s@\'localhost\' IDENTIFIED BY %s',(user,pw))
        query(c,f"GRANT '{role}' TO %s@'localhost'",(user,))
        query(c,f"SET DEFAULT ROLE '{role}' TO %s@'localhost'",(user,))
        login=connect(user=user,password=pw)
        checks=[]
        try:
            tests=[('consulta paciente','SELECT cpf FROM paciente LIMIT 1',role!='Estagiario'),('visão restrita','SELECT * FROM vw_paciente_restrito LIMIT 1',role!='Fisioterapeuta'),('excluir cadastro','DELETE FROM paciente WHERE id_paciente=4294967295',role=='Administrador')]
            if role=='Fisioterapeuta':tests.append(('inserir evolução','INSERT INTO evolucao SELECT * FROM evolucao WHERE 1=0',True))
            for label,sql,allowed in tests:
                try:query(login,sql);actual=True;code=None
                except pymysql.MySQLError as e:actual=False;code=e.args[0]
                assert actual==allowed,(role,label,code)
                checks.append({'operation':label,'expected_allowed':allowed,'allowed':actual,'mysql_error':code})
            if role=='Estagiario':
                query(c,"GRANT SELECT ON clinica_fisioterapia.sala TO %s@'localhost'",(user,));query(login,'SELECT * FROM sala LIMIT 1')
                query(c,"REVOKE SELECT ON clinica_fisioterapia.sala FROM %s@'localhost'",(user,))
                try:query(login,'SELECT * FROM sala LIMIT 1');raise AssertionError('REVOKE ineficaz')
                except pymysql.MySQLError as e:assert e.args[0]==1142
                checks.append({'operation':'GRANT/REVOKE SELECT sala','allowed_before_revoke':True,'denied_after_revoke':True})
            result['profiles'].append({'role':role,'current_role':scalar(login,'SELECT CURRENT_ROLE()'),'checks':checks})
        finally:login.close()

    # Sucesso e falha na segunda instrução da operação original sessão/evolução.
    # Usamos horário histórico válido e IDs temporários, não os defaults duplicados da UI.
    tx=connect(autocommit=False)
    try:
        query(tx,"INSERT INTO sessao VALUES (1,6,'2026-02-17 11:00:00','REALIZADA',1)")
        query(tx,"INSERT INTO evolucao VALUES (1,6,'Evidência temporária','Resposta informada no teste')");tx.commit()
        assert scalar(c,'SELECT COUNT(*) FROM evolucao WHERE id_tratamento=1 AND numero_sessao=6')==1
        query(tx,"INSERT INTO sessao VALUES (1,7,'2026-02-17 12:00:00','REALIZADA',1)")
        try:query(tx,'INSERT INTO evolucao VALUES (1,7,NULL,NULL)');raise AssertionError('NOT NULL não impediu')
        except pymysql.MySQLError as e:failure=e.args[0];tx.rollback()
        assert scalar(c,'SELECT COUNT(*) FROM sessao WHERE id_tratamento=1 AND numero_sessao=7')==0
        result['transaction']={'committed_session_and_evolution':True,'second_statement_failure':failure,'session_absent_after_rollback':True,'scope':'Operação equivalente em SQL; tela original de sessão não foi corrigida.'}
        query(tx,"UPDATE evolucao SET resposta_observada='Alteração de teste' WHERE id_tratamento=1 AND numero_sessao=6");tx.commit()
        query(tx,'DELETE FROM sessao WHERE id_tratamento=1 AND numero_sessao=6');tx.commit()
        audit=query(c,'SELECT acao,conduta_anterior,conduta_nova,resposta_anterior,resposta_nova,usuario_db,data_hora FROM auditoria_evolucao WHERE id_tratamento=1 AND numero_sessao=6')
        assert {r[0] for r in audit}>={'UPDATE','DEL_CASC'}
        result['audit']={'rows':audit,'note':'UPDATE direto e DELETE em cascata da sessão; conta administrativa de teste identificada.'}
    finally:
        tx.rollback();tx.close();query(c,'DELETE FROM sessao WHERE id_tratamento=1 AND numero_sessao IN (6,7)');query(c,'DELETE FROM auditoria_evolucao WHERE id_tratamento=1 AND numero_sessao IN (6,7)')
    (out/'resultados.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str)+'\n')
    print('PASS benchmarks, concorrência, três logins/perfis, GRANT/REVOKE, transação e auditoria')
finally:
    for user in users:query(c,"DROP USER IF EXISTS %s@'localhost'",(user,))
    c.close()
