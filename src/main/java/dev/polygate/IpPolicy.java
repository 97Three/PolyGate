package dev.polygate;

import java.net.InetAddress;

final class IpPolicy {
  private IpPolicy() {}

  static boolean valid(String rule) {
    try {
      parse(rule);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static boolean matches(String address, String rule) {
    try {
      var network = parse(rule);
      if (!literal(address)) return false;
      byte[] client = InetAddress.getByName(address).getAddress();
      if (client.length != network.address.length) return false;
      for (int bit = 0; bit < network.bits; bit++)
        if ((client[bit / 8] & (1 << (7 - bit % 8)))
            != (network.address[bit / 8] & (1 << (7 - bit % 8)))) return false;
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static Network parse(String rule) throws Exception {
    String[] parts = rule.split("/", -1);
    if (parts.length > 2 || !literal(parts[0])) throw new IllegalArgumentException();
    byte[] address = InetAddress.getByName(parts[0]).getAddress();
    int bits = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
    if (bits < 0 || bits > address.length * 8) throw new IllegalArgumentException();
    return new Network(address, bits);
  }

  private static boolean literal(String value) {
    if (value.contains(":")) return value.matches("[0-9a-fA-F:.]+");
    if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
    for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) return false;
    return true;
  }

  private record Network(byte[] address, int bits) {}
}
