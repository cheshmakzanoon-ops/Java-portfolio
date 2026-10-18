package io.github.cheshmakzanoon.portfolio.byteguard.cfg;

import java.util.*;

public record BasicBlock(int first, int last, List<Integer> successors) {
  public BasicBlock {
    successors = List.copyOf(successors);
  }
}
