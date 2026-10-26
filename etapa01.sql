import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** SQL fixo: nomes de tabela/coluna e valores nunca vêm de concatenação da tela. */
public final class CrudRepository {
    public enum Entity {
        PACIENTE(new String[]{"CPF (11 dígitos)","Nome","Nascimento (AAAA-MM-DD)","Telefone (opcional)","Situação (ATIVO/INATIVO)","ID convênio (opcional)"},
            "SELECT cpf,nome,data_nascimento,telefone,situacao,id_convenio FROM paciente WHERE id_paciente=?",
            "INSERT INTO paciente(cpf,nome,data_nascimento,telefone,situacao,id_convenio) VALUES (?,?,?,?,?,?)",
            "UPDATE paciente SET cpf=?,nome=?,data_nascimento=?,telefone=?,situacao=?,id_convenio=? WHERE id_paciente=?",
            "DELETE FROM paciente WHERE id_paciente=?"),
        FISIOTERAPEUTA(new String[]{"CPF (11 dígitos)","Nome","Telefone (opcional)","E-mail (opcional)","CREFITO"},
            "SELECT p.cpf,p.nome,p.telefone,p.email,f.crefito FROM profissional p JOIN fisioterapeuta f ON f.id_profissional=p.id_profissional WHERE p.id_profissional=?",
            "INSERT INTO profissional(cpf,nome,telefone,email) VALUES (?,?,?,?)",
            "UPDATE profissional SET cpf=?,nome=?,telefone=?,email=? WHERE id_profissional=?",
            "DELETE p FROM profissional p JOIN fisioterapeuta f ON f.id_profissional=p.id_profissional WHERE p.id_profissional=?"),
        SALA(new String[]{"Código","Capacidade"},
            "SELECT codigo,capacidade FROM sala WHERE id_sala=?",
            "INSERT INTO sala(codigo,capacidade) VALUES (?,?)",
            "UPDATE sala SET codigo=?,capacidade=? WHERE id_sala=?",
            "DELETE FROM sala WHERE id_sala=?"),
        EXERCICIO(new String[]{"Nome","Região do corpo","Descrição (opcional)"},
            "SELECT nome,regiao_corpo,descricao FROM exercicio WHERE id_exercicio=?",
            "INSERT INTO exercicio(nome,regiao_corpo,descricao) VALUES (?,?,?)",
            "UPDATE exercicio SET nome=?,regiao_corpo=?,descricao=? WHERE id_exercicio=?",
            "DELETE FROM exercicio WHERE id_exercicio=?");
        public final String[] fields;
        final String select,insert,update,delete;
        Entity(String[] fields,String select,String insert,String update,String delete) {
            this.fields=fields;this.select=select;this.insert=insert;this.update=update;this.delete=delete;
        }
    }
    public static long positiveId(String text) {
        try { long id=Long.parseLong(text.trim());if(id>0 && id<=4294967295L)return id; }
        catch(NumberFormatException ignored) {}
        throw new IllegalArgumentException("Informe um ID inteiro positivo válido.");
    }
    static Object[] validate(Entity e,String[] raw) {
        if(raw.length!=e.fields.length)throw new IllegalArgumentException("Campos incompletos.");
        String[] v=Arrays.stream(raw).map(String::trim).toArray(String[]::new);
        Object[] a=Arrays.copyOf(v,v.length,Object[].class);
        int[] required=switch(e){case PACIENTE->new int[]{0,1,2,4};case FISIOTERAPEUTA->new int[]{0,1,4};case SALA->new int[]{0,1};case EXERCICIO->new int[]{0,1};};
        for(int i:required)if(v[i].isEmpty())throw new IllegalArgumentException("Preencha: "+e.fields[i]);
        int[] limits=switch(e){case PACIENTE->new int[]{11,150,10,20,7,10};case FISIOTERAPEUTA->new int[]{11,150,20,254,30};case SALA->new int[]{30,10};case EXERCICIO->new int[]{100,100,16000};};
        for(int i=0;i<v.length;i++)if(v[i].length()>limits[i])throw new IllegalArgumentException("Campo muito longo: "+e.fields[i]);
        if(e==Entity.PACIENTE || e==Entity.FISIOTERAPEUTA)
            if(!v[0].matches("[0-9]{11}"))throw new IllegalArgumentException("CPF deve conter 11 dígitos, sem pontuação.");
        if(e==Entity.PACIENTE) {
            try { LocalDate d=LocalDate.parse(v[2]);if(d.isAfter(LocalDate.now()))throw new IllegalArgumentException("Nascimento não pode ser futuro.");a[2]=java.sql.Date.valueOf(d); }
            catch(java.time.format.DateTimeParseException ex){throw new IllegalArgumentException("Data inválida. Use AAAA-MM-DD.");}
            if(!Set.of("ATIVO","INATIVO").contains(v[4]))throw new IllegalArgumentException("Situação deve ser ATIVO ou INATIVO.");
            a[3]=v[3].isEmpty()?null:v[3];a[5]=v[5].isEmpty()?null:positiveId(v[5]);
        } else if(e==Entity.FISIOTERAPEUTA) {
            a[2]=v[2].isEmpty()?null:v[2];a[3]=v[3].isEmpty()?null:v[3];
        } else if(e==Entity.SALA)a[1]=positiveId(v[1]);
        else a[2]=v[2].isEmpty()?null:v[2];
        return a;
    }
    static void bind(PreparedStatement s,Object[] a,int count)throws SQLException {
        for(int i=0;i<count;i++)s.setObject(i+1,a[i]);
    }
    public String[] find(Entity e,long id)throws SQLException {
        try(Connection c=DbConfig.open();PreparedStatement s=c.prepareStatement(e.select)) {
            s.setLong(1,id);try(ResultSet r=s.executeQuery()) {
                if(!r.next())return null;
                String[] v=new String[e.fields.length];for(int i=0;i<v.length;i++)v[i]=Objects.toString(r.getString(i+1),"");return v;
            }
        }
    }
    public long save(Entity e,Long id,String[] raw)throws SQLException {
        Object[] a=validate(e,raw);
        try(Connection c=DbConfig.open()) {
            c.setAutoCommit(false);
            try {
                if(id!=null && e==Entity.FISIOTERAPEUTA) {
                    try(PreparedStatement s=c.prepareStatement("SELECT id_profissional FROM fisioterapeuta WHERE id_profissional=? FOR UPDATE")) {
                        s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Registro não encontrado.","02000");}
                    }
                }
                int count=e==Entity.FISIOTERAPEUTA?4:a.length;
                long key=id==null?0:id;
                try(PreparedStatement s=c.prepareStatement(id==null?e.insert:e.update,Statement.RETURN_GENERATED_KEYS)) {
                    bind(s,a,count);if(id!=null)s.setLong(count+1,id);
                    if(s.executeUpdate()!=1)throw new SQLException("Registro não encontrado.","02000");
                    if(id==null)try(ResultSet r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("ID não retornado.");key=r.getLong(1);}
                }
                if(e==Entity.FISIOTERAPEUTA) {
                    String sql=id==null?"INSERT INTO fisioterapeuta(crefito,id_profissional) VALUES (?,?)":"UPDATE fisioterapeuta SET crefito=? WHERE id_profissional=?";
                    try(PreparedStatement s=c.prepareStatement(sql)){s.setObject(1,a[4]);s.setLong(2,key);s.executeUpdate();}
                }
                c.commit();return key;
            } catch(SQLException|RuntimeException ex){try{c.rollback();}catch(SQLException rb){ex.addSuppressed(rb);}throw ex;}
        }
    }
    public boolean delete(Entity e,long id)throws SQLException {
        try(Connection c=DbConfig.open();PreparedStatement s=c.prepareStatement(e.delete)) {
            s.setLong(1,id);return s.executeUpdate()==1;
        }
    }
}
