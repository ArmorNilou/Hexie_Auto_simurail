package willow.train.hexietrain;

public enum DispatcherState {
   IDLE("待命"),
   RUNNING("行驶中"),
   COASTING("滑车"),
   BRAKING("急刹");

   private final String label;

   private DispatcherState(String label) {
      this.label = label;
   }

   public String label() {
      return this.label;
   }
}
