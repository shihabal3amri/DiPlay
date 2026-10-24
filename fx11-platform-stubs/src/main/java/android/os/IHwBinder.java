// Compile-only signature. Android supplies this interface at runtime.
package android.os;
public interface IHwBinder {
    interface DeathRecipient { void serviceDied(long cookie); }
}
