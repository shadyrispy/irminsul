// Irminsul Parser Build Script
// 编译 auto-artifactarium 的 protobuf 文件

fn main() {
    // auto-artifactarium 已经生成了 protobuf 代码，我们不需要重新生成
    // 只需要引用生成的代码
    println!("cargo:rerun-if-changed=auto-artifactarium/protos/protos.proto");
}