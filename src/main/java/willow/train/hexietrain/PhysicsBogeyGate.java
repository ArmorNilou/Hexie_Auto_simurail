package willow.train.hexietrain;

public final class PhysicsBogeyGate {
   private static Boolean cached;

   private PhysicsBogeyGate() {
   }

   public static boolean available() {
      if (cached != null) {
         return cached;
      } else {
         try {
            Class.forName("com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity");
            cached = Boolean.TRUE;
         } catch (Throwable var1) {
            cached = Boolean.FALSE;
         }

         return cached;
      }
   }
}
