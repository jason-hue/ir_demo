package cn.edu.bistu.cs.ir.ai;

import org.springframework.stereotype.Service;

@Service
public class AiFallbackService {

    private final ProviderStatusService providerStatusService;

    public AiFallbackService(ProviderStatusService providerStatusService) {
        this.providerStatusService = providerStatusService;
    }

    public AiFallbackMode currentMode() {
        return providerStatusService.snapshot().fallbackMode();
    }

    public boolean isLexicalOnlyMode() {
        return currentMode() == AiFallbackMode.LEXICAL_ONLY;
    }
}
