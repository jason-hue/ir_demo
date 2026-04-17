package cn.edu.bistu.cs.ir.ai;

public record ProviderStatus(
        String provider,
        ProviderAvailabilityState state,
        boolean enabled,
        String detail
) {
}
