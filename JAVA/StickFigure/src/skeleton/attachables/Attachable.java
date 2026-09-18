package skeleton.attachables;

// -----------------------------------------------------------------------------
public class Attachable {
    int priority;
    public String type;
    public String name;

    public Attachable(){
        priority = 0;
        type = "default";
        name = "";
    }

    protected Attachable(String type, String name){
        priority = 0;
        this.type = type;
        this.name = name;
    }
}
