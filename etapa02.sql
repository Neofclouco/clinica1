import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Painel único para formulários; cada entidade possui SQL e campos fixos no repositório. */
public final class CrudPanel extends JPanel {
    private final CrudRepository repository=new CrudRepository();
    private final CrudRepository.Entity entity;
    private final JTextField id=new JTextField();
    private final JTextField[] values;
    private final java.util.List<JButton> buttons=new ArrayList<>();
    private final DefaultTableModel model;
    private final JLabel status=new JLabel("Pronto. Para criar, o ID é gerado pelo banco.");
    public CrudPanel(CrudRepository.Entity entity) {
        super(new BorderLayout(8,8));this.entity=entity;
        setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JPanel form=new JPanel(new GridLayout(0,2,6,6));form.add(new JLabel("ID para consulta, alteração ou exclusão"));form.add(id);
        values=new JTextField[entity.fields.length];
        for(int i=0;i<values.length;i++){form.add(new JLabel(entity.fields[i]));values[i]=new JTextField();form.add(values[i]);}
        if(entity==CrudRepository.Entity.PACIENTE)values[4].setText("ATIVO");
        JPanel top=new JPanel(new BorderLayout(5,5));top.add(form,BorderLayout.CENTER);
        JPanel actions=new JPanel();top.add(actions,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
        String[] columns=new String[entity.fields.length+1];columns[0]="ID";System.arraycopy(entity.fields,0,columns,1,entity.fields.length);
        model=new DefaultTableModel(columns,0){public boolean isCellEditable(int r,int c){return false;}};
        add(new JScrollPane(new JTable(model)),BorderLayout.CENTER);add(status,BorderLayout.SOUTH);
        button(actions,"Criar",()->save(false));button(actions,"Consultar por ID",this::find);
        button(actions,"Atualizar",()->save(true));button(actions,"Excluir",this::delete);
    }
    private void button(JPanel panel,String label,Runnable action) {
        JButton b=new JButton(label);buttons.add(b);panel.add(b);b.addActionListener(e->{try{action.run();}catch(IllegalArgumentException ex){status.setText(ex.getMessage());}});
    }
    private String[] data(){return Arrays.stream(values).map(JTextField::getText).toArray(String[]::new);}
    private void save(boolean update) {
        Long key=update?CrudRepository.positiveId(id.getText()):null;String[] snapshot=data();
        run(()->repository.save(entity,key,snapshot),saved->{id.setText(saved.toString());showRow(saved,snapshot);status.setText(update?"Registro atualizado.":"Registro criado. ID: "+saved);});
    }
    private void find() {
        long key=CrudRepository.positiveId(id.getText());
        run(()->repository.find(entity,key),row->{model.setRowCount(0);if(row==null){status.setText("Registro não encontrado.");return;}for(int i=0;i<row.length;i++)values[i].setText(row[i]);showRow(key,row);status.setText("Registro consultado.");});
    }
    private void delete() {
        long key=CrudRepository.positiveId(id.getText());
        if(JOptionPane.showConfirmDialog(this,"Excluir o registro "+key+"?","Confirmar exclusão",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        run(()->repository.delete(entity,key),deleted->{status.setText(deleted?"Registro excluído.":"Registro não encontrado.");if(deleted){model.setRowCount(0);id.setText("");for(JTextField f:values)f.setText("");if(entity==CrudRepository.Entity.PACIENTE)values[4].setText("ATIVO");}});
    }
    private void showRow(long key,String[] row){model.setRowCount(0);Object[] r=new Object[row.length+1];r[0]=key;System.arraycopy(row,0,r,1,row.length);model.addRow(r);}
    private <T> void run(Callable<T> work,Consumer<T> done) {
        buttons.forEach(b->b.setEnabled(false));status.setText("Processando...");
        new SwingWorker<T,Void>(){
            protected T doInBackground()throws Exception{return work.call();}
            protected void done(){try{done.accept(get());}catch(Exception ex){Throwable c=ex.getCause()==null?ex:ex.getCause();status.setText(message(c));}finally{buttons.forEach(b->b.setEnabled(true));}}
        }.execute();
    }
    static String message(Throwable ex) {
        if(ex instanceof IllegalArgumentException || ex instanceof IllegalStateException)return ex.getMessage();
        if(ex instanceof SQLException s){
            if(s.getErrorCode()==1062)return "CPF, CREFITO, código ou nome já cadastrado.";
            if(s.getErrorCode()==1451)return "Exclusão impedida: registro possui vínculos (tratamentos, sessões ou supervisão).";
            if(s.getErrorCode()==1452)return "O convênio informado não existe.";
            if(s.getErrorCode()==1142 || s.getErrorCode()==1143)return "Seu perfil não tem permissão para esta operação.";
            if("02000".equals(s.getSQLState()))return "Registro não encontrado.";
            if("45000".equals(s.getSQLState()))return "Uma regra de negócio impediu a operação. Revise os dados.";
            if(s.getSQLState()!=null && s.getSQLState().startsWith("08"))return "Falha de conexão com o banco.";
            if(s.getErrorCode()==1045)return "Acesso negado. Confira a configuração da conta.";
            System.err.println("Falha SQL: estado="+s.getSQLState()+", código="+s.getErrorCode());
        }
        return "Operação não concluída. Verifique a configuração e os dados.";
    }
}
